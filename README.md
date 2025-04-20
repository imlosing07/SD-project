## Flujo
Mandar 
(Main.java) > main 
(PeerRunner.java) > handleCommand
(PeerHandle.java) > sendmsg [hilo no bloqueante]
(Peer.java) > sendmsg
(Connection.java) > send(Message)
(PeerHandle.java) > PingFutureListener [hilo terminado]

Recepcion
(PeerChannelHandler.java) > channelRead0
(TextMessage.java) > handle
(Peer.java) > handlesendmsg
(TextMessage.java) > ImprimirMensaje
## Compilar 
`mvn clean package`

## Ejecutar 
`java -DpeerName=<nombre> -jar target/p2p.jar -n <nombre> -b <puerto>`  

