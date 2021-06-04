# JSON-VLAN-ENRICHMENT
Enrich a JSON data stream using a JSONPath and the customer's lookup table

### Build and Execution Environment
* Java 8
* Confluent Platform 5.5.x or newer

## Build
Use Maven to build the KStream Application.

```
mvn clean package
```

A successful build will create a target directory with the following two jar files:
* json-vlan-enrichment-0.1.jar
* json-vlan-enrichment-0.1-jar-with-dependencies.jar

The `json-vlan-enrichment-0.1-jar-with-dependencies.jar` file contains all the dependencies needed to run the application. Therefore, this dependencies jar file should be the file executed when running the KStream Application.

## Execution Configuration
The KStream Application requires a configuration properties file.

Example:
```
application.id=enrich-some-json
bootstrap.servers=big-host-1.datadisorder.dev:9092
schema.registry.url=http://big-host-1.datadisorder.dev:8081

input.topic.name=bro_sample
output.topic.name=bro_enriched
error.topic.name=bro_error

security.protocol=PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin";

jsonpath.to.vlan.id=$['id.vlan']
jsonpath.for.customer=customer

confluent.metrics.reporter.bootstrap.servers=big-host-1.datadisorder.dev:9092
confluent.metrics.reporter.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin-secret";
confluent.metrics.reporter.sasl.mechanism=PLAIN
confluent.metrics.reporter.security.protocol=PLAINTEXT
```

With the above configuration, the KStreams application will connect to the Kafka Brokers identified by the `bootstrap.servers` cluster making use of the `security.protocol` and `sasl.` configuration. The KStreams Application will use a consumer group with the `application.id` and read its input from `input.topic.name` and write out the parsed events to `output.topic.name`. If any configured exceptions are caught with the `input.topic.name` deserialization or parsing, the event will not be written to `output.topic.name`, and will instead be written to `error.topic.name`. To horizontally scale the KStream, make sure the `input.topic.name` has multiple partitions and start another jvm with the same configuration properties file.

## Execution
Run the `json-vlan-enrichment-0.1-jar-with-dependencies.jar` with Java 8.

```
java -jar json-vlan-enrichment-0.1-jar-with-dependencies.jar bro.properties
```


## Test
Create input/output/error topics
```
$ kafka-topics --bootstrap-server big-host-1.datadisorder.dev:9092 --create --topic bro_sample --replication-factor 1 --partitions 3 --command-config configuration/dev.properties

$ kafka-topics --bootstrap-server big-host-1.datadisorder.dev:9092 --create --topic bro_enriched --replication-factor 1 --partitions 3 --command-config configuration/dev.properties

$ kafka-topics --bootstrap-server big-host-1.datadisorder.dev:9092 --create --topic bro_error --replication-factor 1 --partitions 3 --command-config configuration/dev.properties
```

or

```
PROPERTIES=configuration/bro.properties
for topic in `cat $PROPERTIES | grep "topic.name" | cut -d "=" -f 2`; do \
echo "Create $topic"; \
kafka-topics --bootstrap-server big-host-1.datadisorder.dev:9092 --create --topic $topic --replication-factor 1 --partitions 3 --command-config $PROPERTIES; \
done
```

Assign ACLs if needed
```
TODO
```

Start the Kafka Stream (see Execution Configuration and Execution)

Push the sample ELFF messages
```
jq -rc . test/bro-sample.json | kafka-console-producer --bootstrap-server big-host-1.datadisorder.dev:9092 --topic bro_sample
jq -rc . test/frp-sample-rec_type_2.json | kafka-console-producer --bootstrap-server big-host-1.datadisorder.dev:9092 --topic jrss.initial.ops.all.conus.meamd.frp.json
```

Validate the messages were parsed using the Confluent Control Center or with kafkacat

```
kafkacat -F configuration/kafkacat.properties -b  big-host-1.datadisorder.dev:9092 -C -t bro_enriched
```
