/* 
 * Propiedad de sistemas 
 * DpeerName --> nombre del peer
 * Parametros
 * -n --> nombre del peer
 * -b --> puerto a escuchar
 * -c --> configuracion opcional
 */

package com.basrikahveci.p2p;

import com.basrikahveci.p2p.peer.Config;
import com.google.common.base.Charsets;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Properties;

public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final String PEER_NAME_SYSTEM_PROPERTY = "peerName";

    private static final String PEER_NAME_PARAMETER = "n";

    private static final String BIND_PORT_PARAMETER = "b";

    private static final String CONFIG_FILE_PARAMETER = "c";

    private static final String HELP_PARAMETER = "help";

    private enum ConfigProperty {

        MIN_NUMBER_OF_ACTIVE_CONNECTIONS("minActiveConnections") {
            @Override
            public void setIntValue(int val, Config config) {
                config.setMinNumberOfActiveConnections(val);
            }
        },

        MAX_READ_IDLE_SECONDS("maxReadIdleSeconds") {
            @Override
            public void setIntValue(int val, Config config) {
                config.setMaxReadIdleSeconds(val);
            }
        },

        KEEP_ALIVE_PING_PERIOD_SECONDS("keepAlivePingPeriodSeconds") {
            @Override
            public void setIntValue(int val, Config config) {
                config.setKeepAlivePeriodSeconds(val);
            }
        },

        PING_TIMEOUT_SECONDS("pingTimeoutSeconds") {
            @Override
            public void setIntValue(int val, Config config) {
                config.setPingTimeoutSeconds(val);
            }
        },

        AUTO_DISCOVERY_PING_FREQUENCY("autoDiscoveryPingFrequency") {
            @Override
            public void setIntValue(int val, Config config) {
                config.setAutoDiscoveryPingFrequency(val);
            }
        },

        PING_TTL("pingTTL") {
            @Override
            public void setIntValue(int val, Config config) {
                config.setPingTTL(val);
            }
        },

        LEADER_ELECTION_TIMEOUT("leaderElectionTimeoutSeconds") {
            @Override
            public void setIntValue(int val, Config config) {
                config.setLeaderElectionTimeoutSeconds(val);
            }
        },

        LEADER_REJECTION_TIMEOUT("leaderRejectionTimeoutSeconds") {
            @Override
            public void setIntValue(int val, Config config) {
                config.setLeaderRejectionTimeoutSeconds(val);
            }
        };

        public static ConfigProperty byPropertyName(final String propertyName) {
            for (ConfigProperty prop : values()) {
                if (prop.propertyName.equals(propertyName)) {
                    return prop;
                }
            }

            throw new IllegalArgumentException("Invalid config property: " + propertyName);
        }

        private final String propertyName;

        ConfigProperty(String propertyName) {
            this.propertyName = propertyName;
        }

        public abstract void setIntValue(final int val, final Config config);

    }
    // Metodo principal 
    public static void main(String[] args) throws IOException, InterruptedException {
        // Evaluar si el nombre del peer es nulo
        if (System.getProperty(PEER_NAME_SYSTEM_PROPERTY) == null) {
            LOGGER.error("Se debe proporcionar la propiedad Dpeername!");
            System.exit(-1);
        }

        final OptionSet options = parseArguments(args); //procesar los argumentos
        final PeerRunner peerRunner = createPeerRunner(options); // crear el peer
        peerRunner.start(); //iniciar el peer

        String line;
        final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, Charsets.UTF_8));
        while ((line = reader.readLine()) != null) {
            final PeerRunner.CommandResult result = peerRunner.handleCommand(line); //manejar el comando
            if (result == PeerRunner.CommandResult.SHUT_DOWN) {
                break;
            } else if (result == PeerRunner.CommandResult.INVALID_COMMAND && line.length() > 0) {
                printHelp(line);
            }
        }
        System.exit(0);
    }

    private static PeerRunner createPeerRunner(final OptionSet options) throws IOException, InterruptedException {
        final String peerName = (String) options.valueOf(PEER_NAME_PARAMETER);
        final int portToBind = (int) options.valueOf(BIND_PORT_PARAMETER);
        // Configuracion incial
        final Config config = new Config();
        // Configuracion por usuario
        config.setPeerName(peerName);
        populateConfig(options, config); //configurar segun el archivo

        return new PeerRunner(config, portToBind);
    }
    // parsear la opciones
    private static OptionSet parseArguments(final String[] args) throws IOException {
        final OptionParser optionParser = new OptionParser();
        // tipo que acepta el parser
        optionParser.accepts(PEER_NAME_PARAMETER).withRequiredArg().ofType(String.class).describedAs("peer name");
        optionParser.accepts(BIND_PORT_PARAMETER).withRequiredArg().ofType(Integer.class).describedAs("port to bind");
        optionParser.accepts(CONFIG_FILE_PARAMETER).withOptionalArg().ofType(File.class).describedAs("config properties file");
        optionParser.accepts(HELP_PARAMETER).forHelp();

        final OptionSet options = optionParser.parse(args);
        // mostrar ayuda
        if (options.has(HELP_PARAMETER)) {
            optionParser.printHelpOn(System.out);
        }
        // si falta nombre o puerto mostrar ayuda y cerrar
        if (!options.has(PEER_NAME_PARAMETER) || !options.has(BIND_PORT_PARAMETER)) {
            if (!options.has(HELP_PARAMETER)) {
                optionParser.printHelpOn(System.out);
            }
            LOGGER.error("Faltan Argumentos!!");
            System.exit(-1);
        }

        return options;
    }
    // Terminar la configuracion 
    private static void populateConfig(final OptionSet options, final Config config) throws IOException {
        if (options.has(CONFIG_FILE_PARAMETER)) {
            final File file = (File) options.valueOf(CONFIG_FILE_PARAMETER);
            loadConfig(config, file); //cargar configuracion opcional
        }

        LOGGER.info("Config: {}", config);
    }

    private static void loadConfig(final Config config, final File file) throws IOException {
        final Properties properties = new Properties();
        // Abrir el archivo de propiedades
        final FileInputStream fileInputStream = new FileInputStream(file);
        properties.load(fileInputStream);
        fileInputStream.close();
        // Iterar sobre las propiedades y asignar los valores a la configuracion
        for (String propertyName : properties.stringPropertyNames()) {
            final ConfigProperty configProperty = ConfigProperty.byPropertyName(propertyName);
            final int val = Integer.parseInt(properties.getProperty(propertyName));
            configProperty.setIntValue(val, config);
        }
    }

    private static void printHelp(final String line) {
        if (!"help".equalsIgnoreCase(line.trim())) {
            System.out.println("Comando no valido:  " + line);
        }

        System.out.println(
                "############################################## COMANDOS ###############################################");
        System.out.println(
                "# 1) ping                   >>> Enumera peers en la red                                               #");
        System.out.println(
                "# 2) leave                  >>> Salirse de la red                                                     #");
        System.out.println(
                "# 3) connect host port      >>> Se conecta a un peer a un host y puerto especificado                  #");
        System.out.println(
                "# 4) disconnect peerName    >>> Se desconecta del peer especificado con peerName                      #");
        System.out.println(
                "# 5) election               >>> Comienza una nueva eleccion de lider                                  #");
        System.out.println(
                "# 6) sendmsg peerName msg   >>> Mandar un mensaje a un peer especifico                                #");
        System.out.println(
                "# 7) send peerName file msg >>> Mandar un archivo y un mensaje opcional                               #");
        System.out.println(
                "# 8) list                   >>> Listar archivos del peer                                              #");
        System.out.println(
                "#######################################################################################################");
    }

}
