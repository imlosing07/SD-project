package com.basrikahveci.p2p.peer.service;

import com.basrikahveci.p2p.peer.Config;
import com.basrikahveci.p2p.peer.Peer;
import com.basrikahveci.p2p.peer.network.Connection;
import com.basrikahveci.p2p.peer.network.PeerChannelHandler;
import com.basrikahveci.p2p.peer.network.PeerChannelInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ObjectEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Maintains TCP connections between this peer and its neighbours
 */
public class ConnectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionService.class);

    private final Config config;

    private final EventLoopGroup networkEventLoopGroup;

    private final EventLoopGroup peerEventLoopGroup;

    private final ObjectEncoder encoder;

    // server name -> connection
    private final Map<String, Connection> connections = new HashMap<String, Connection>();

    public ConnectionService(Config config, EventLoopGroup networkEventLoopGroup, EventLoopGroup peerEventLoopGroup,
                             ObjectEncoder encoder) {
        this.config = config;
        this.networkEventLoopGroup = networkEventLoopGroup;
        this.peerEventLoopGroup = peerEventLoopGroup;
        this.encoder = encoder;
    }

    public void addConnection(final Connection connection) {
        final String peerName = connection.getPeerName();
        final Connection previousConnection = connections.put(peerName, connection);

        LOGGER.info("Conexion a " + peerName + " se agrega.");

        if (previousConnection != null) {
            previousConnection.close();
            LOGGER.warn("Conexion ya existente a " + peerName + " esta cerrado.");
        }
    }

    public boolean removeConnection(final Connection connection) {
        final boolean removed = connections.remove(connection.getPeerName()) != null;

        if (removed) {
            LOGGER.info(connection + " se elimina de las conexiones!");
        } else {
            LOGGER.warn("Conexion a " + connection.getPeerName() + " no se elimina porque no se encuentra en las conexiones!");
        }

        return removed;
    }

    public int getNumberOfConnections() {
        return connections.size();
    }

    public boolean isConnectedTo(final String peerName) {
        return connections.containsKey(peerName);
    }

    public Connection getConnection(final String peerName) {
        return connections.get(peerName);
    }

    public Collection<Connection> getConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    public void connectTo(final Peer peer, final String host, final int port, final CompletableFuture<Void> futureToNotify) {
        final PeerChannelHandler handler = new PeerChannelHandler(config, peer);
        final PeerChannelInitializer initializer = new PeerChannelInitializer(config, encoder, peerEventLoopGroup, handler);
        final Bootstrap clientBootstrap = new Bootstrap();
        clientBootstrap.group(networkEventLoopGroup).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true)
                .handler(initializer);

        final ChannelFuture connectFuture = clientBootstrap.connect(host, port);
        if (futureToNotify != null) {
            connectFuture.addListener(new ChannelFutureListener() {
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        futureToNotify.complete(null);
                        LOGGER.info("Conexion exitosamente a {}:{}", host, port);
                    } else {
                        futureToNotify.completeExceptionally(future.cause());
                        LOGGER.error("No se pudo conectar a " + host + ":" + port, future.cause());
                    }
                }
            });
        }
    }

}
