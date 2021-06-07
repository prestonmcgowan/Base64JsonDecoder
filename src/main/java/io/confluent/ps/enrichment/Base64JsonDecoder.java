package io.confluent.ps.enrichment;

import static org.apache.kafka.common.serialization.Serdes.String;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.Base64;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.WallclockTimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;

/**
* Parse JSON with JSONPath in a KStream.
*/
public final class Base64JsonDecoder {
    private final Logger log = LoggerFactory.getLogger(Base64JsonDecoder.class);

    /**
    * Parse JSON with JSONPath Constructor.
    */
    private Base64JsonDecoder() {
    }

    /**
    * Setup the Streams Processors we will be using from the passed in configuration.properties.
    * @param envProps Environment Properties file
    * @return Properties Object ready for KafkaStreams Topology Builder
    */
    protected Properties buildStreamsProperties(Properties envProps) {
        Properties props = new Properties();

        props.put(StreamsConfig.APPLICATION_ID_CONFIG, envProps.getProperty("application.id"));
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, envProps.getProperty("bootstrap.servers"));
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, String().getClass());
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, envProps.getProperty("security.protocol"));
        props.put(StreamsConfig.SECURITY_PROTOCOL_CONFIG, envProps.getProperty("security.protocol"));
        props.put(SaslConfigs.SASL_MECHANISM, envProps.getProperty("sasl.mechanism"));
        props.put(SaslConfigs.SASL_JAAS_CONFIG, envProps.getProperty("sasl.jaas.config"));

        log.debug("SASL Config------");
        log.debug("bootstrap.servers={}", envProps.getProperty("bootstrap.servers"));
        log.debug("security.protocol={}", envProps.getProperty("security.protocol"));
        log.debug("sasl.mechanism={}",    envProps.getProperty("sasl.mechanism"));
        log.debug("sasl.jaas.config={}",  envProps.getProperty("sasl.jaas.config"));
        log.debug("-----------------");

        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        props.put("schema.registry.url", envProps.getProperty("schema.registry.url"));


        // Broken negative timestamp
        props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, WallclockTimestampExtractor.class.getName());

        props.put(StreamsConfig.PRODUCER_PREFIX + ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
            "io.confluent.monitoring.clients.interceptor.MonitoringProducerInterceptor");

        props.put(StreamsConfig.MAIN_CONSUMER_PREFIX + ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
            "io.confluent.monitoring.clients.interceptor.MonitoringConsumerInterceptor");

        return props;
    }

    /**
    * Build the topology from the loaded configuration
    * @param Properties built by the buildStreamsProperties
    * @return The build topology
    */
    protected Topology buildTopology(Properties envProps) {
        log.debug("Starting buildTopology");
        final String inputTopicName           = envProps.getProperty("input.topic.name");
        final String outputTopicName          = envProps.getProperty("output.topic.name");
        final String jsonpathToBase64         = envProps.getProperty("jsonpath.to.base64");
        final String removeFromBase64         = envProps.getProperty("remove.from.base64");
        final String jsonpathForDecodedBase64 = envProps.getProperty("jsonpath.for.decoded.base64");

        log.debug("JSON PATH Settings: jsonpathToBase64 {} jsonpathForDecodedBase64 {}", jsonpathToBase64, jsonpathForDecodedBase64);
        log.debug("Remove from Payload: {}", removeFromBase64);

        final StreamsBuilder builder = new StreamsBuilder();

        // topic contains byte data
        final KStream<String, String> jsonStream =
        builder.stream(inputTopicName, Consumed.with(Serdes.String(), Serdes.String()));


        jsonStream.mapValues(jsonData -> {
            log.trace("JSON Data: {}", jsonData);
            DocumentContext jsonDoc = JsonPath.parse(jsonData);

            String base64 = "";
            try {
                base64 = JsonPath.parse(jsonData).read(jsonpathToBase64, String.class);
            } catch(PathNotFoundException e) {
                log.info("JSONPath did not find a value");
            }
            if (base64.length() > 0) {
                log.debug("base64 = {}", base64);
                base64 = base64.replace(removeFromBase64, "");
                log.debug("base64 after removal = {}", base64);
                byte[] decodedBytes = Base64.getDecoder().decode(base64);
                String decoded = new String(decodedBytes);
                log.debug("decoded = {}", decoded);
                DocumentContext decodedAsJson = JsonPath.parse(decoded);
                log.debug("Decoded JSON Data: {}", decodedAsJson.jsonString());
                jsonDoc.put("$", jsonpathForDecodedBase64, decodedAsJson.json());
            } else {
                jsonDoc.put("$", jsonpathForDecodedBase64, null);
            }
            String newJson = jsonDoc.jsonString();
            log.debug("New JSON Data: {}", newJson);
            return newJson;
        })
        .to(outputTopicName, Produced.with(Serdes.String(), Serdes.String()));

        return builder.build();
    }


    /**
    * Load in the Environment Properties that were passed in from the CLI.
    * @param fileName
    * @return
    * @throws IOException
    */
    protected Properties loadEnvProperties(String fileName) throws IOException {
        Properties envProps = new Properties();

        try (
        FileInputStream input = new FileInputStream(fileName);
        ) {
            envProps.load(input);
        }
        return envProps;
    }

    /**
    * Main function that handles the life cycle of the Kafka Streams app.
    * @param configPath
    * @throws IOException
    */
    private void run(String configPath) throws IOException {

        Properties envProps = this.loadEnvProperties(configPath);
        Properties streamProps = this.buildStreamsProperties(envProps);

        Topology topology = this.buildTopology(envProps);

        final KafkaStreams streams = new KafkaStreams(topology, streamProps);
        final CountDownLatch latch = new CountDownLatch(1);

        // Attach shutdown handler to catch Control-C.
        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                streams.close(Duration.ofSeconds(5));
                latch.countDown();
            }
        });

        try {
            streams.cleanUp();
            streams.start();
            latch.await();
        } catch (Throwable e) {
            System.exit(1);
        }
        System.exit(0);
    }

    private static void exampleProperties() {
        System.out.println("Please create a configuration properties file and pass it on the command line as an argument");
        System.out.println("Sample env.properties:");
        System.out.println("----------------------------------------------------------------");
        System.out.println("application.id=extract-base64");
        System.out.println("bootstrap.servers=big-host-1.datadisorder.dev:9092");
        System.out.println("schema.registry.url=http://big-host-1.datadisorder.dev:8081");
        System.out.println("");
        System.out.println("input.topic.name=base64-encoded");
        System.out.println("output.topic.name=base64-decoded");
        System.out.println("");
        System.out.println("jsonpath.to.base64=$['payload']");
        System.out.println("jsonpath.for.decoded.base64=decoded");
        System.out.println("");
        System.out.println("security.protocol=PLAINTEXT");
        System.out.println("sasl.mechanism=PLAIN");
        System.out.println("sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\"admin\";");
        System.out.println("");
        System.out.println("confluent.metrics.reporter.bootstrap.servers=big-host-1.datadisorder.dev:9092");
        System.out.println("confluent.metrics.reporter.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\"admin-secret\";");
        System.out.println("confluent.metrics.reporter.sasl.mechanism=PLAIN");
        System.out.println("confluent.metrics.reporter.security.protocol=PLAINTEXT");
        System.out.println("----------------------------------------------------------------");
    }

    /**
    *  Run this with an arg for the properties file
    * @param args
    * @throws IOException
    */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            exampleProperties();
            throw new IllegalArgumentException("This program takes one argument: the path to an environment configuration file.");
        }

        new Base64JsonDecoder().run(args[0]);
    }
}
