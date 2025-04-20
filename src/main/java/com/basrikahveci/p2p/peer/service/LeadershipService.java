package com.basrikahveci.p2p.peer.service;

import com.basrikahveci.p2p.peer.Config;
import com.basrikahveci.p2p.peer.Peer;
import com.basrikahveci.p2p.peer.network.Connection;
import com.basrikahveci.p2p.peer.network.message.leader.AnnounceLeader;
import com.basrikahveci.p2p.peer.network.message.leader.Election;
import com.basrikahveci.p2p.peer.network.message.leader.Rejection;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class LeadershipService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeadershipService.class);

    private final ConnectionService connectionService;

    private final Config config;

    private final EventLoopGroup peerEventLoopGroup;

    /**
     * Name of the peer that is announced as leader
     */
    private String leaderName;

    /**
     * Flag that indicates if this peer has started an election currently
     */
    private boolean isElectionPresent;

    /**
     * Timeout {@link Future} of the current election
     */
    private ScheduledFuture electionTimeoutFuture;

    public LeadershipService(ConnectionService connectionService, Config config, EventLoopGroup peerEventLoopGroup) {
        this.connectionService = connectionService;
        this.config = config;
        this.peerEventLoopGroup = peerEventLoopGroup;
    }

    /**
     * Called when a peer is announced by another peer. Also propagates the leader name to its own neighbourhoods.
     * If announced leader is weaker than this peer, this peer starts an election.
     *
     * @param connection Connection of the peer that announces the leader
     * @param leaderName Name of the peer that is announced as leader
     */
    public void handleLeader(final Connection connection, final String leaderName) {
        final String connectionPeerName = connection.getPeerName();
        if (this.leaderName != null) {
            if (this.leaderName.equals(leaderName)) {
                LOGGER.warn("Ignorando el nuevo lider {} de {} ya que es un lider conocido.", leaderName, connectionPeerName);
                return;
            }
            final boolean isNewLeaderStronger = !isThisPeerStrongerThan(leaderName);
            LOGGER.warn("Se recibe un nuevo lider {} de {} mientras ya hay un lider {}. ¿Es el nuevo lider mas fuerte? {}",
                    leaderName, connectionPeerName, this.leaderName, isNewLeaderStronger);
        }

        this.leaderName = leaderName;
        LOGGER.info("El lider de {} se establece en {}.", connectionPeerName, leaderName);

        if (isThisPeerStrongerThan(leaderName)) {
            LOGGER.info("Comenzando una nueva eleccion ya que el lider anunciado {} es mas debil.", leaderName);
            startElection();
        }
    }

    /**
     * Called when another peer starts an election. It this peer is stronger than the other peer that starts the
     * election, then this peer rejects the other node and starts a new election itself.
     *
     * @param connection Connection of the peer that starts the election
     */
    public void handleElection(final Connection connection) {
        final String connectionPeerName = connection.getPeerName();

        if (leaderName != null) {
            LOGGER.warn("Se recibio una eleccion de {} mientras ya hay un lider {}. Comienza una nueva eleccion.",
                    connectionPeerName, leaderName);
        } else {
            LOGGER.info("Se recibio el mensaje de eleccion de {}.", connectionPeerName);
        }

        if (isThisPeerStrongerThan(connectionPeerName)) {
            connection.send(new Rejection());
            LOGGER.info("Rechazando la eleccion de {} por ser mas debil.", connectionPeerName);
            scheduleElection();
        }
    }

    /**
     * Called when another peer rejects the election started by this peer. Once its election is rejected, this peer
     * waits for some time to check if a leader will be announced or not. If not announced, it starts a new election.
     *
     * @param connection Connection of the peer that rejects the election of this peer
     */
    public void handleRejection(final Connection connection) {
        final String connectionPeerName = connection.getPeerName();
        if (isElectionPresent) {
            if (isThisPeerStrongerThan(connectionPeerName)) {
                LOGGER.warn("El rechazo de {} se ignora porque es mas debil.", connectionPeerName);
            } else {
                LOGGER.info("{} rechazado. Se agoto el tiempo de espera para la programacion de elecciones.", connectionPeerName);
                if (electionTimeoutFuture != null) {
                    electionTimeoutFuture.cancel(false);
                    electionTimeoutFuture = null;
                } else {
                    LOGGER.warn("¡El tiempo limite para las elecciones no existe!");
                }

                scheduleElectionTimeout(config.getLeaderRejectionTimeoutSeconds());
                isElectionPresent = false;
            }
        } else {
            LOGGER.debug("El rechazo de {} se ignora ya que no hay eleccion", connectionPeerName);
        }
    }

    /**
     * Starts a new election. It does not start the new election immediately. It schedules it to a few millis later
     * to reduce congestion between election of this peer and other peers.
     */
    public void scheduleElection() {
        peerEventLoopGroup.schedule((Runnable) this::startElection, Peer.RANDOM.nextInt(100), MILLISECONDS);
    }

    /**
     * Returns the current leader
     *
     * @return current leader
     */
    public String getLeaderName() {
        return leaderName;
    }

    private boolean isThisPeerStrongerThan(final String otherPeerName) {
        return config.getPeerName().compareTo(otherPeerName) > 0;
    }

    private void startElection() {
        if (isElectionPresent) {
            LOGGER.warn("Ya existe una eleccion en curso!");
            return;
        }

        isElectionPresent = true;
        leaderName = null;
        final Election election = new Election();
        for (Connection connection : connectionService.getConnections()) {
            if (connection.getPeerName().compareTo(config.getPeerName()) > 0) {
                connection.send(election);
            }
        }

        LOGGER.info("Comenzo una eleccion!");
        scheduleElectionTimeout(config.getLeaderElectionTimeoutSeconds());
    }

    private void scheduleElectionTimeout(final long timeoutSeconds) {
        electionTimeoutFuture = peerEventLoopGroup.schedule((Runnable) this::handleElectionTimeout, timeoutSeconds, SECONDS);
    }

    private void handleElectionTimeout() {
        electionTimeoutFuture = null;
        if (isElectionPresent) {
            LOGGER.info("Las elecciones se agotaron sin recibir ningun rechazo. Se anuncia como lider.");
            setThisPeerLeader();
            isElectionPresent = false;
        } else if (leaderName == null) {
            LOGGER.info("Elecciones rechazadas, pero aun no hay lider. Comienzan nuevas elecciones.");
            scheduleElection();
        } else {
            LOGGER.debug("Se agoto el tiempo de eleccion y el lider actual ya esta establecido en {}", leaderName);
        }
    }

    private void setThisPeerLeader() {
        this.leaderName = config.getPeerName();
        LOGGER.info("Anunciando a si mismo como lider!");
        final AnnounceLeader announceLeader = new AnnounceLeader(config.getPeerName());
        for (Connection connection : connectionService.getConnections()) {
            connection.send(announceLeader);
        }
    }

}
