package com.basrikahveci.p2p;

import com.basrikahveci.p2p.peer.Config;
import com.basrikahveci.p2p.peer.PeerHandle;
import io.netty.channel.ChannelFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.BiConsumer;

import static com.basrikahveci.p2p.PeerRunner.CommandResult.INVALID_COMMAND;

public class PeerRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerHandle.class);

    enum CommandResult {
        CONTINUE, SHUT_DOWN, INVALID_COMMAND
    }

    private final PeerHandle handle;
    private WebServer webServer;

    public PeerRunner(final Config config, final int portToBind) {
        handle = new PeerHandle(config, portToBind);
    }

    public ChannelFuture start() throws InterruptedException {
        // Initialize web server on a different port (P2P port + 1000)
        int webPort = handle.getPort() + 1000;
        webServer = new WebServer(this, webPort);
        webServer.start();
        LOGGER.info("Web interface started on port {}", webPort);

        return handle.start();
    }

    public CommandResult handleCommand(final String command) {
        CommandResult result = CommandResult.CONTINUE;
        try {
            if (command.equals("ping")) {
                handle.ping().whenComplete(new PingFutureListener());
            } else if (command.equals("leave")) {
                handle.leave().whenComplete(new LeaveFutureListener());
                if (webServer != null) {
                    webServer.stop();
                }
                result = CommandResult.SHUT_DOWN;
            } else if (command.startsWith("connect ")) {
                final String[] tokens = command.split(" ");
                final String hostToConnect = tokens[1];
                final int portToConnect = Integer.parseInt(tokens[2]);
                handle.connect(hostToConnect, portToConnect).whenComplete(new ConnectFutureListener(hostToConnect, portToConnect));
            } else if (command.startsWith("disconnect ")) {
                final String[] tokens = command.split(" ");
                handle.disconnect(tokens[1]);
            } else if (command.equals("election")) {
                handle.scheduleLeaderElection();
            } else if (command.startsWith("sendfile ")) {
                // Usar un parser más sofisticado para respetar las comillas
                List<String> tokens = parseCommandWithQuotes(command);

                if (tokens.size() != 3) {
                    System.out.println("Invalid sendfile command. Format: sendfile peerName filePath");
                    return INVALID_COMMAND;
                }

                final String targetPeer = tokens.get(1);
                final String filePath = tokens.get(2);

                final File file = new File(filePath);

                if (!file.exists()) {
                    System.out.println("Error: File not found: " + filePath);
                    return INVALID_COMMAND;
                }

                System.out.println("Sending file: " + file.getAbsolutePath() + " to peer: " + targetPeer);
                handle.sendFile(targetPeer, file).whenComplete(new SendFileFutureListener(targetPeer, file));
            } else if (command.equals("files")) {
                // List received files
                // [código existente para listar archivos]
            } else {
                result = INVALID_COMMAND;
            }
        } catch (Exception e) {
            LOGGER.error("Command failed: " + command, e);
            result = INVALID_COMMAND;
        }
        return result;
    }

    // Método auxiliar para analizar correctamente comandos con comillas
    private List<String> parseCommandWithQuotes(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inQuotes = false;

        for (char c : command.toCharArray()) {
            if (c == ' ' && !inQuotes) {
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken = new StringBuilder();
                }
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else {
                currentToken.append(c);
            }
        }

        // Añadir el último token si existe
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
        }

        return tokens;
    }

    public PeerHandle getHandle() {
        return handle;
    }

    private static class PingFutureListener implements BiConsumer<Collection<String>, Throwable> {

        @Override
        public void accept(Collection<String> peerNames, Throwable throwable) {
            if (peerNames != null) {
                LOGGER.info("PEERS: {}", peerNames);
            } else {
                LOGGER.error("PING FAILED!", throwable);
            }
        }
    }

    private static class LeaveFutureListener implements BiConsumer<Void, Throwable> {

        @Override
        public void accept(Void result, Throwable throwable) {
            if (throwable == null) {
                LOGGER.info("LEFT THE CLUSTER AT {}", new Date());
            } else {
                LOGGER.error("EXCEPTION OCCURRED DURING LEAVING THE CLUSTER!", throwable);
            }
        }
    }

    private static class ConnectFutureListener implements BiConsumer<Void, Throwable> {

        private final String hostToConnect;

        private final int portToConnect;

        public ConnectFutureListener(String hostToConnect, int portToConnect) {
            this.hostToConnect = hostToConnect;
            this.portToConnect = portToConnect;
        }

        @Override
        public void accept(Void aVoid, Throwable throwable) {
            if (throwable == null) {
                LOGGER.info("Successfully connected to {}:{}", hostToConnect, portToConnect);
            } else {
                LOGGER.error("Connection to " + hostToConnect + ":" + portToConnect + " failed!", throwable);
            }
        }
    }

    private static class SendFileFutureListener implements BiConsumer<Void, Throwable> {
        private final String targetPeer;
        private final File file;

        public SendFileFutureListener(String targetPeer, File file) {
            this.targetPeer = targetPeer;
            this.file = file;
        }

        @Override
        public void accept(Void aVoid, Throwable throwable) {
            if (throwable == null) {
                LOGGER.info("Successfully initiated file transfer of {} to {}", file.getName(), targetPeer);
            } else {
                LOGGER.error("Failed to send file " + file.getName() + " to " + targetPeer, throwable);
            }
        }
    }
}