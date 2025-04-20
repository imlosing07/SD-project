package com.basrikahveci.p2p.peer.network.message;

import com.basrikahveci.p2p.peer.Peer;
import com.basrikahveci.p2p.peer.network.Connection;

public class TextMessage implements Message {

    private static final long serialVersionUID = 1L;
    private final String content;
    private final String nombre;
    private final String type;

    public TextMessage(String content, String nombre, String type) {
        this.content = content;
        this.nombre = nombre;
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void ImprimirMensaje() {
        if (type.equals("return")){
            System.out.println("********************** Mensaje recibido correctamente de '" + nombre + "'");
        }else if (type.equals("message")){
            System.out.println("********************** Mensaje de '" + nombre + "' **********************");
            System.out.println(content);
        }else if (type.equals("file")){
            System.out.println("********************** Mandado por '" + nombre + "' **********************");
            if (!"".equals(content)) System.out.println(content);
        } else {
            System.out.println("********************** Mensaje de '" + nombre + "' **********************");
            if (!"".equals(content)) System.out.println(content);
        }
    }


    @Override
    public void handle(Peer peer, Connection connection) {
        peer.handlesendmsg(connection, this);
    }

    @Override
    public String toString() {
        return "Mensaje{" +
            "nombre=" + nombre +
            ", content=" + content +
            '}';
    }
}
