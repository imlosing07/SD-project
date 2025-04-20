package com.basrikahveci.p2p.peer;

import com.basrikahveci.p2p.peer.network.Connection;
import com.basrikahveci.p2p.peer.network.message.FileMessage;
import com.basrikahveci.p2p.peer.network.message.TextMessage;
import com.basrikahveci.p2p.peer.network.message.ping.CancelPongs;
import com.basrikahveci.p2p.peer.network.message.ping.Ping;
import com.basrikahveci.p2p.peer.network.message.ping.Pong;
import com.basrikahveci.p2p.peer.service.ConnectionService;
import com.basrikahveci.p2p.peer.service.LeadershipService;
import com.basrikahveci.p2p.peer.service.PingService;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static java.lang.Math.min;

public class Peer {

    private static final Logger LOGGER = LoggerFactory.getLogger(Peer.class);

    public static final Random RANDOM = new Random();

    private final Config config;

    private final ConnectionService connectionService;

    private final PingService pingService;

    private final LeadershipService leadershipService;

    private Channel bindChannel;

    private boolean running = true;

    public Peer(Config config, ConnectionService connectionService, PingService pingService, LeadershipService leadershipService) {
        this.config = config;
        this.connectionService = connectionService;
        this.pingService = pingService;
        this.leadershipService = leadershipService;
    }

    public void handleConnectionOpened(Connection connection, String leaderName) {
        if (isShutdown()) {
            LOGGER.warn("Nueva conexion de {} ignorada ya que no se esta ejecutando", connection.getPeerName());
            return;
        }

        if (connection.getPeerName().equals(config.getPeerName())) {
            LOGGER.error("No se puede conectar consigo mismo. Cerrando nueva conexion.");
            connection.close();
            return;
        }

        connectionService.addConnection(connection);
        if (leaderName != null) {
            final String currentLeaderName = leadershipService.getLeaderName();
            if (currentLeaderName == null) {
                leadershipService.handleLeader(connection, leaderName);
            } else if (!leaderName.equals(currentLeaderName)) {
                LOGGER.info("El lider conocido {} y el lider {} anunciado por {} son diferentes.", currentLeaderName, leaderName, connection);
                leadershipService.scheduleElection();
            }
        }
        pingService.propagatePingsToNewConnection(connection);
    }

    public void handleConnectionClosed(Connection connection) {
        if (connection == null) {
            return;
        }

        final String connectionPeerName = connection.getPeerName();
        if (connectionPeerName == null || connectionPeerName.equals(config.getPeerName())) {
            return;
        }

        if (connectionService.removeConnection(connection)) {
            cancelPings(connection, connectionPeerName);
            cancelPongs(connectionPeerName);
        }

        if (connectionPeerName.equals(leadershipService.getLeaderName())) {
            LOGGER.warn("Se esta iniciando una eleccion ya que la conexion con el lider actual {} esta cerrada.", connectionPeerName);
            leadershipService.scheduleElection();
        }
    }

    public void cancelPings(final Connection connection, final String removedPeerName) {
        if (running) {
            pingService.cancelPings(connection, removedPeerName);
        } else {
            LOGGER.warn("Pings de {} no se pueden cancelar porque no se estan ejecutando", removedPeerName);
        }
    }

    public void handlePing(Connection connection, Ping ping) {
        if (running) {
            pingService.handlePing((InetSocketAddress) bindChannel.localAddress(), connection, ping);
        } else {
            LOGGER.warn("Ping de {} se ignora porque no se esta ejecutando", connection.getPeerName());
        }
    }

    public void handlePong(Connection connection, Pong pong) {
        if (running) {
            pingService.handlePong(pong);
        } else {
            LOGGER.warn("Pong of {} se ignora porque no se esta ejecutando", connection.getPeerName());
        }
    }

    public void keepAlivePing() {
        if (isShutdown()) {
            LOGGER.warn("Ping periodico ignorado ya que no se ejecuta");
            return;
        }

        final int numberOfConnections = connectionService.getNumberOfConnections();
        if (numberOfConnections > 0) {
            final boolean discoveryPingEnabled = numberOfConnections < config.getMinNumberOfActiveConnections();
            pingService.keepAlive(discoveryPingEnabled);
        } else {
            LOGGER.debug("No hay ping automatico porque no hay conexion");
        }
    }

    public void timeoutPings() {
        if (isShutdown()) {
            LOGGER.warn("Los pings de tiempo de espera se ignoran porque no se estan ejecutando");
            return;
        }

        final Collection<Pong> pongs = pingService.timeoutPings();
        final int availableConnectionSlots =
                config.getMinNumberOfActiveConnections() - connectionService.getNumberOfConnections();

        if (availableConnectionSlots > 0) {
            List<Pong> notConnectedPeers = new ArrayList<>();
            for (Pong pong : pongs) {
                if (!config.getPeerName().equals(pong.getPeerName()) && !connectionService
                        .isConnectedTo(pong.getPeerName())) {
                    notConnectedPeers.add(pong);
                }
            }

            Collections.shuffle(notConnectedPeers);
            for (int i = 0, j = min(availableConnectionSlots, notConnectedPeers.size()); i < j; i++) {
                final Pong peerToConnect = notConnectedPeers.get(i);
                final String host = peerToConnect.getServerHost();
                final int port = peerToConnect.getServerPort();
                LOGGER.info("Conexion automatica a {} a traves de {}:{}", peerToConnect.getPeerName(), peerToConnect.getPeerName(), host,
                        port);
                connectTo(host, port, null);
            }
        }
    }

    public void cancelPongs(final String removedPeerName) {
        if (isShutdown()) {
            LOGGER.warn("Pongs de {} no cancelados ya que no se ejecutan", removedPeerName);
            return;
        }

        pingService.cancelPongs(removedPeerName);
    }

    public void handleLeader(final Connection connection, String leaderName) {
        if (isShutdown()) {
            LOGGER.warn("Anuncio de lider de {} desde la conexion {} ignorado porque no se esta ejecutando", leaderName, connection.getPeerName());
            return;
        }

        leadershipService.handleLeader(connection, leaderName);
    }

    public void handleElection(final Connection connection) {
        if (isShutdown()) {
            LOGGER.warn("Eleccion de {} ignorado porque no se esta ejecutando", connection.getPeerName());
            return;
        }

        leadershipService.handleElection(connection);
    }

    public void handleRejection(final Connection connection) {
        if (isShutdown()) {
            LOGGER.warn("Rechazo de {} ignorado ya que no se esta ejecutando", connection.getPeerName());
            return;

        }

        leadershipService.handleRejection(connection);
    }

    public void scheduleElection() {
        if (isShutdown()) {
            LOGGER.warn("Elecciones no programadas por no haberse realizado");
            return;
        }

        leadershipService.scheduleElection();
    }

    public void disconnect(final String peerName) {
        if (isShutdown()) {
            LOGGER.warn("No se ha desconectado de {} porque no se esta ejecutando", peerName);
            return;
        }

        final Connection connection = connectionService.getConnection(peerName);
        if (connection != null) {
            LOGGER.info("Desconectando este peer {} de {}", config.getPeerName(), peerName);
            connection.close();
        } else {
            LOGGER.warn("Este peer {} no esta conectado a {}", config.getPeerName(), peerName);
        }
    }

    public String getLeaderName() {
        return leadershipService.getLeaderName();
    }

    public void setBindChannel(final Channel bindChannel) {
        this.bindChannel = bindChannel;
    }

    public void ping(final CompletableFuture<Collection<String>> futureToNotify) {
        if (isShutdown()) {
            futureToNotify.completeExceptionally(new RuntimeException("Desconectado!"));
            return;
        }

        pingService.ping(futureToNotify);
    }

    public void leave(final CompletableFuture<Void> futureToNotify) {
        if (isShutdown()) {
            LOGGER.warn("{} ya cerrado!", config.getPeerName());
            futureToNotify.complete(null);
            return;
        }

        bindChannel.closeFuture().addListener(future -> {
            if (future.isSuccess()) {
                futureToNotify.complete(null);
            } else {
                futureToNotify.completeExceptionally(future.cause());
            }
        });

        pingService.cancelOwnPing();
        pingService.cancelPongs(config.getPeerName());
        final CancelPongs cancelPongs = new CancelPongs(config.getPeerName());
        for (Connection connection : connectionService.getConnections()) {
            connection.send(cancelPongs);
            connection.close();
        }
        bindChannel.close();
        running = false;
    }

    public void connectTo(final String host, final int port, final CompletableFuture<Void> futureToNotify) {
        if (running) {
            connectionService.connectTo(this, host, port, futureToNotify);
        } else {
            futureToNotify.completeExceptionally(new RuntimeException("El servidor no se esta ejecutando"));
        }
    }

    private boolean isShutdown() {
        return !running;
    }
    // nuevos metodos
    public void sendmsg(final String peerName,final String content, final CompletableFuture<String> futureToNotify) {
        if (isShutdown()) {
            futureToNotify.completeExceptionally(new RuntimeException("Desconectado!"));
            return;
        }
        if (config.getPeerName().equals(peerName)) {
            futureToNotify.completeExceptionally(new RuntimeException("No se puede enviar un mensaje a si mismo"));
            return;
        }
        final Connection connection = connectionService.getConnection(peerName);
        // validar conexion
        if (connection == null) {
            futureToNotify.completeExceptionally(new RuntimeException("No hay conexion con el peerName " + peerName));
            return;
        }
        connection.send(new TextMessage(content, config.getPeerName(),"message")); 
        futureToNotify.complete("Se mando a " + peerName + " el contenido " + content);
    }

    public void handlesendmsg(Connection connection, TextMessage textMessage) {
        if (running) {
            textMessage.ImprimirMensaje();
            // evitar bucle 
            if (!"return".equals(textMessage.getType())){
                connection.send(new TextMessage("", config.getPeerName(),"return"));
            }
        } else {
            LOGGER.warn("El mensaje {} se ignora porque no se esta ejecutando", connection.getPeerName());
        }
    }

    public void sendfile(final String peerName,final String file,final String content, final CompletableFuture<String> futureToNotify) {
        if (isShutdown()) {
            futureToNotify.completeExceptionally(new RuntimeException("Desconectado!"));
            return;
        }
        if (config.getPeerName().equals(peerName)) {
            futureToNotify.completeExceptionally(new RuntimeException("No se puede enviar un mensaje a si mismo"));
            return;
        }
        FileMessage fileMessage = new FileMessage(content, config.getPeerName(), file);
        if (!fileMessage.isValido()){
            futureToNotify.completeExceptionally(new RuntimeException("Archivo no valido"));
            return;
        }
        final Connection connection = connectionService.getConnection(peerName);
        // validar conexion
        if (connection == null) {
            futureToNotify.completeExceptionally(new RuntimeException("No hay conexion con el peerName " + peerName));
            return;
        }
        connection.send(fileMessage); 
        futureToNotify.complete("Se mando a " + peerName + " el contenido " + file);
    }

    public void handlesendfile(Connection connection, FileMessage fileMessage) {
        if (running) {
            fileMessage.ImprimirMensaje();
            // evitar bucle 
            if (!"return".equals(fileMessage.getType())){
                connection.send(new TextMessage("", config.getPeerName(),"return"));
                fileMessage.saveFile(config.getPeerName());
            }
        } else {
            LOGGER.warn("El mensaje {} se ignora porque no se esta ejecutando", connection.getPeerName());
        }
    }

}
