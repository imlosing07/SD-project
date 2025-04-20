package com.basrikahveci.p2p;

import com.basrikahveci.p2p.peer.Config;
import com.basrikahveci.p2p.peer.PeerHandle;
import io.netty.channel.ChannelFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.function.BiConsumer;

import static com.basrikahveci.p2p.PeerRunner.CommandResult.INVALID_COMMAND;

public class PeerRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerHandle.class);

    enum CommandResult {
        CONTINUE,
        SHUT_DOWN,
        INVALID_COMMAND
    }

    private final PeerHandle handle;

    public PeerRunner(final Config config, final int portToBind) {
        handle = new PeerHandle(config, portToBind);
    }

    public ChannelFuture start() throws InterruptedException {
        return handle.start();
    }
    // Evaluar comandos y ejecuta la accion correspondiente
    public CommandResult handleCommand(final String command) {
        CommandResult result = CommandResult.CONTINUE;
        try {
            if (command.equals("ping")) {
                handle.ping().whenComplete(new PingFutureListener());
            } else if (command.equals("leave")) {
                handle.leave().whenComplete(new LeaveFutureListener());
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
            // nueva opcion
            } else if (command.startsWith("sendmsg")) {
                final String[] tokens = command.split(" ");
                String nombre = tokens[1];
                String content = String.join(" ", Arrays.copyOfRange(tokens, 2, tokens.length));
                handle.sendmsg(nombre, content).whenComplete(new sendmsgFutureListener());
            } else if (command.startsWith("send")) {
                final String[] tokens = command.split(" ");
                String nombre = tokens[1];
                String file = tokens[2];
                String content = String.join(" ", Arrays.copyOfRange(tokens, 3, tokens.length));
                handle.sendfile(nombre, file, content).whenComplete(new sendmsgFutureListener());
            } else if (command.equals("list")) {
                handle.listFiles();
            } else {
                result = INVALID_COMMAND;
            }
        } catch (Exception e) {
            LOGGER.error("Command failed: " + command, e);
            result = INVALID_COMMAND;
        }

        return result;
    }

    private static class PingFutureListener implements BiConsumer<Collection<String>, Throwable> {

        @Override
        public void accept(Collection<String> peerNames, Throwable throwable) {
            if (peerNames != null) {
                LOGGER.info("PEERS: {}", peerNames);
            } else {
                LOGGER.error("PING FALLIDO!", throwable);
            }
        }
    }

    private static class LeaveFutureListener implements BiConsumer<Void, Throwable> {

        @Override
        public void accept(Void result, Throwable throwable) {
            if (throwable == null) {
                LOGGER.info("ABANDONO DEL GRUPO A LAS {}", new Date());
            } else {
                LOGGER.error("¡SE PRODUJO UNA EXCEPCION AL ABANDONAR EL GRUPO!", throwable);
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
                LOGGER.info("Conectado exitosamente a {}:{}", hostToConnect, portToConnect);
            } else {
                LOGGER.error("Conectado a " + hostToConnect + ":" + portToConnect + " fallo!", throwable);
            }
        }
    }
    // nuevos metodos 
    private static class sendmsgFutureListener implements BiConsumer<String, Throwable> {

        @Override
        public void accept(String peerNames, Throwable throwable) {
            if (peerNames != null) {
                LOGGER.info("Se mando correctamente a : {}", peerNames);
            } else {
                LOGGER.error("ENVIO DE MENSAJE FALLIDO!", throwable);
            }
        }
    }

}
