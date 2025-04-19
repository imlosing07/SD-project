package com.basrikahveci.p2p.peer.network.message;

import com.basrikahveci.p2p.file.FileProtocol;
import com.basrikahveci.p2p.peer.Peer;
import com.basrikahveci.p2p.peer.network.Connection;

/**
 * Adapter class that wraps FileProtocol.FileMessage objects and makes them
 * compatible with the Message interface
 */
public class FileTransferMessage implements Message {

    private final FileProtocol.FileMessage fileMessage;

    public FileTransferMessage(FileProtocol.FileMessage fileMessage) {
        this.fileMessage = fileMessage;
    }

    @Override
    public void handle(Peer peer, Connection connection) {
        // This delegates handling to the peer's file message handler
        peer.handleFileMessage(fileMessage);
    }

    public FileProtocol.FileMessage getFileMessage() {
        return fileMessage;
    }
}