package com.basrikahveci.p2p.peer;

import com.basrikahveci.p2p.file.FileProtocol;
import com.basrikahveci.p2p.peer.network.PeerChannelHandler;
import com.basrikahveci.p2p.peer.network.PeerChannelInitializer;
import com.basrikahveci.p2p.peer.service.ConnectionService;
import com.basrikahveci.p2p.peer.service.LeadershipService;
import com.basrikahveci.p2p.peer.service.PingService;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.basrikahveci.p2p.file.FileTransferManager;

import java.io.File;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

public class PeerHandle {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerHandle.class);

    private final Config config;

    private final int portToBind;

    private final EventLoopGroup acceptorEventLoopGroup = new NioEventLoopGroup(1);

    private final EventLoopGroup networkEventLoopGroup = new NioEventLoopGroup(6);

    private final EventLoopGroup peerEventLoopGroup = new NioEventLoopGroup(1);

    private final ObjectEncoder encoder = new ObjectEncoder();

    private final Peer peer;

    private Future keepAliveFuture;

    private Future timeoutPingsFuture;

    private FileTransferManager fileTransferManager;

    public PeerHandle(Config config, int portToBind) {
        this.config = config;
        this.portToBind = portToBind;
        final ConnectionService connectionService = new ConnectionService(config, networkEventLoopGroup, peerEventLoopGroup, encoder);
        final LeadershipService leadershipService = new LeadershipService(connectionService, config, peerEventLoopGroup);
        final PingService pingService = new PingService(connectionService, leadershipService, config);

        String uploadDir = "./uploads/" + config.getPeerName();
        this.fileTransferManager = new FileTransferManager(this, uploadDir);

        // Pasar el fileTransferManager al construir el Peer
        this.peer = new Peer(config, connectionService, pingService, leadershipService, fileTransferManager);
    }

    public String getPeerName() {
        return config.getPeerName();
    }

    public ChannelFuture start() throws InterruptedException {
        ChannelFuture closeFuture = null;

        final PeerChannelHandler peerChannelHandler = new PeerChannelHandler(config, peer);
        final PeerChannelInitializer peerChannelInitializer = new PeerChannelInitializer(config, encoder, peerEventLoopGroup, peerChannelHandler);
        final ServerBootstrap peerBootstrap = new ServerBootstrap();
        peerBootstrap.group(acceptorEventLoopGroup, networkEventLoopGroup).channel(NioServerSocketChannel.class).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000).option(ChannelOption.SO_KEEPALIVE, true).option(ChannelOption.SO_BACKLOG, 100).handler(new LoggingHandler(LogLevel.INFO)).childHandler(peerChannelInitializer);

        final ChannelFuture bindFuture = peerBootstrap.bind(portToBind).sync();

        if (bindFuture.isSuccess()) {
            LOGGER.info("{} Successfully bind to {}", config.getPeerName(), portToBind);
            final Channel serverChannel = bindFuture.channel();

            final SettableFuture<Void> setServerChannelFuture = SettableFuture.create();
            peerEventLoopGroup.execute(() -> {
                try {
                    peer.setBindChannel(serverChannel);
                    setServerChannelFuture.set(null);
                } catch (Exception e) {
                    setServerChannelFuture.setException(e);
                }
            });

            try {
                setServerChannelFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.error("Couldn't set bind channel to server " + config.getPeerName(), e);
                System.exit(-1);
            }

            final int initialDelay = Peer.RANDOM.nextInt(config.getKeepAlivePeriodSeconds());

            this.keepAliveFuture = peerEventLoopGroup.scheduleAtFixedRate((Runnable) peer::keepAlivePing, initialDelay, config.getKeepAlivePeriodSeconds(), SECONDS);

            this.timeoutPingsFuture = peerEventLoopGroup.scheduleAtFixedRate((Runnable) peer::timeoutPings, 0, 100, TimeUnit.MILLISECONDS);

            closeFuture = serverChannel.closeFuture();
        } else {
            LOGGER.error(config.getPeerName() + " could not bind to " + portToBind, bindFuture.cause());
            System.exit(-1);
        }

        return closeFuture;
    }

    public CompletableFuture<Collection<String>> ping() {
        final CompletableFuture<Collection<String>> future = new CompletableFuture<>();
        peerEventLoopGroup.execute(() -> peer.ping(future));
        return future;
    }

    public CompletableFuture<Void> leave() {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        peerEventLoopGroup.execute(() -> peer.leave(future));
        if (keepAliveFuture != null && timeoutPingsFuture != null) {
            keepAliveFuture.cancel(false);
            timeoutPingsFuture.cancel(false);
            keepAliveFuture = null;
            timeoutPingsFuture = null;
        }
        return future;
    }

    public CompletableFuture<Void> sendFileMetadata(String targetPeer, String transferId, String fileName, String fullPath, long fileSize) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerEventLoopGroup.execute(() -> {
            try {
                FileProtocol.FileMetadataMessage message = new FileProtocol.FileMetadataMessage(transferId, config.getPeerName(), fileName, fullPath, fileSize);

                // Aquí necesitamos adaptar el mensaje para enviarlo con el sistema existente
                // Esto requiere crear una clase adaptadora que implemente Message
                sendFileMessage(targetPeer, message);

                future.complete(null);
            } catch (Exception e) {
                LOGGER.error("Failed to send file metadata for " + fileName + " to " + targetPeer, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> sendFileChunk(String targetPeer, String transferId, byte[] data, long offset, int chunkNumber) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerEventLoopGroup.execute(() -> {
            try {
                FileProtocol.FileChunkMessage message = new FileProtocol.FileChunkMessage(transferId, config.getPeerName(), data, offset, chunkNumber);

                sendFileMessage(targetPeer, message);

                future.complete(null);
            } catch (Exception e) {
                LOGGER.error("Failed to send file chunk at offset " + offset + " to " + targetPeer, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> sendTransferError(String targetPeer, String transferId, String errorMessage) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerEventLoopGroup.execute(() -> {
            try {
                FileProtocol.TransferErrorMessage message = new FileProtocol.TransferErrorMessage(transferId, config.getPeerName(), errorMessage);

                sendFileMessage(targetPeer, message);

                future.complete(null);
            } catch (Exception e) {
                LOGGER.error("Failed to send transfer error notification to " + targetPeer, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> sendChunkAcknowledgment(String targetPeer, String transferId, int chunkNumber) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerEventLoopGroup.execute(() -> {
            try {
                FileProtocol.ChunkAckMessage message = new FileProtocol.ChunkAckMessage(transferId, config.getPeerName(), chunkNumber);
                sendFileMessage(targetPeer, message);

                future.complete(null);
            } catch (Exception e) {
                LOGGER.error("Failed to send chunk acknowledgment for chunk " + chunkNumber + " to " + targetPeer, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> completeFileTransfer(String targetPeer, String transferId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerEventLoopGroup.execute(() -> {
            try {
                FileTransferManager.FileTransferInfo transferInfo = fileTransferManager.getOutgoingTransfer(transferId);
                long totalBytes = transferInfo != null ? transferInfo.getFileSize() : 0;

                FileProtocol.TransferCompleteMessage message = new FileProtocol.TransferCompleteMessage(transferId, config.getPeerName(), totalBytes);

                sendFileMessage(targetPeer, message);

                future.complete(null);
            } catch (Exception e) {
                LOGGER.error("Failed to complete file transfer " + transferId + " to " + targetPeer, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> cancelFileTransfer(String targetPeer, String transferId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerEventLoopGroup.execute(() -> {
            try {
                FileProtocol.TransferCancelMessage message = new FileProtocol.TransferCancelMessage(transferId, config.getPeerName(), "Transfer cancelled by sender");

                sendFileMessage(targetPeer, message);

                future.complete(null);
            } catch (Exception e) {
                LOGGER.error("Failed to cancel file transfer " + transferId + " to " + targetPeer, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Método auxiliar para enviar mensajes de FileProtocol
     */
    private void sendFileMessage(String targetPeer, FileProtocol.FileMessage message) {
        // Now the parameter type matches what peer.sendFileMessage expects
        peer.sendFileMessage(targetPeer, message);
    }

    public CompletableFuture<Void> sendFile(final String targetPeer, final File file) {
        return fileTransferManager.sendFile(targetPeer, file);
    }


    public void scheduleLeaderElection() {
        peerEventLoopGroup.execute(peer::scheduleElection);
    }

    public CompletableFuture<Void> connect(final String host, final int port) {
        final CompletableFuture<Void> connectToHostFuture = new CompletableFuture<>();

        peerEventLoopGroup.execute(() -> peer.connectTo(host, port, connectToHostFuture));

        return connectToHostFuture;
    }

    public void disconnect(final String peerName) {
        peerEventLoopGroup.execute(() -> peer.disconnect(peerName));
    }

    public int getPort() {
        return portToBind;
    }
}
