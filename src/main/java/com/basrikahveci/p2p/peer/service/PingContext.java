package com.basrikahveci.p2p.peer.service;

import com.basrikahveci.p2p.peer.network.Connection;
import com.basrikahveci.p2p.peer.network.message.ping.Ping;
import com.basrikahveci.p2p.peer.network.message.ping.Pong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static java.util.Collections.unmodifiableList;

/**
 * Maintains all information related to an ongoing Ping operation.
 */
public class PingContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(PingContext.class);

    private final Ping ping;

    private final Connection connection;

    // peer name -> pong
    private final Map<String, Pong> pongs = new HashMap<>();

    private final List<CompletableFuture<Collection<String>>> futures = new ArrayList<>();

    public PingContext(Ping ping, Connection connection) {
        this.ping = ping;
        this.connection = connection;
    }

    public String getPeerName() {
        return ping.getPeerName();
    }

    public Ping getPing() {
        return ping;
    }

    public Connection getConnection() {
        return connection;
    }

    public Collection<Pong> getPongs() {
        return Collections.unmodifiableCollection(pongs.values());
    }

    public boolean handlePong(final String thisServerName, final Pong pong) {
        final String pongServerName = pong.getPeerName();
        if (pongs.containsKey(pongServerName)) {
            LOGGER.debug("{} de {} ya esta manejado para {}", pong, pongServerName, ping.getPeerName());
            return false;
        }

        pongs.put(pongServerName, pong);

        LOGGER.debug("Manejo de {} desde {} para {}. Pong #: {}", pong, pongServerName, ping.getPeerName(), pongs.size());

        if (!thisServerName.equals(ping.getPeerName())) {
            if (connection != null) {
                final Pong next = pong.next(thisServerName);
                if (next != null) {
                    LOGGER.debug("Reenviando {} a {} para el iniciador {}", pong, connection.getPeerName(), ping.getPeerName());
                    connection.send(next);
                } else {
                    LOGGER.error("Se recibieron {} no validos de {} para {}", pong, pongServerName, ping.getPeerName());
                }
            } else {
                LOGGER.error("No se encuentra conexion en el contexto de ping para {} desde {} para {}", pong, pongServerName,
                        ping.getPeerName());
            }
        }

        return true;
    }

    public void addFuture(CompletableFuture<Collection<String>> future) {
        futures.add(future);
    }

    public boolean isTimeout() {
        return ping.getPingStartTimestamp() + ping.getPingTimeoutDurationInMillis() <= System.currentTimeMillis();
    }

    public List<CompletableFuture<Collection<String>>> getFutures() {
        return unmodifiableList(futures);
    }

    public boolean removePong(final String serverName) {
        return pongs.remove(serverName) != null;
    }

    @Override
    public String toString() {
        return "PingContext{" +
                "pongs=" + pongs +
                ", connection=" + connection +
                ", ping=" + ping +
                '}';
    }

}
