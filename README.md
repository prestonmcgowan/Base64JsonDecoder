# Base64-JSON-Decoder
Decode base64 data in a JSON data stream using a JSONPath and write it back to the data.

### Build and Execution Environment
* Java 8
* Confluent Platform 5.5.x or newer

## Build
Use Maven to build the KStream Application.

```
mvn clean package
```

A successful build will create a target directory with the following two jar files:
* base64-json-decoder-0.1.jar
* base64-json-decoder-0.1-jar-with-dependencies.jar

The `base64-json-decoder-0.1-jar-with-dependencies.jar` file contains all the dependencies needed to run the application. Therefore, this dependencies jar file should be the file executed when running the KStream Application.

## Execution Configuration
The KStream Application requires a configuration properties file.

Example:
```
application.id=extract-base64
bootstrap.servers=big-host-1.datadisorder.dev:9092
schema.registry.url=http://big-host-1.datadisorder.dev:8081

input.topic.name=base64-encoded
output.topic.name=base64-decoded

jsonpath.to.base64=$['payload']
remove.from.base64=payload=
jsonpath.for.decoded.base64=decoded

security.protocol=PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin";

confluent.metrics.reporter.bootstrap.servers=big-host-1.datadisorder.dev:9092
confluent.metrics.reporter.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin-secret";
confluent.metrics.reporter.sasl.mechanism=PLAIN
confluent.metrics.reporter.security.protocol=PLAINTEXT
```

With the above configuration, the KStreams application will connect to the Kafka Brokers identified by the `bootstrap.servers` cluster making use of the `security.protocol` and `sasl.` configuration. The KStreams Application will use a consumer group with the `application.id` and read its input from `input.topic.name` and write out the parsed events to `output.topic.name`. The base64 encoded data is found in `jsonpath.to.base64`. Before the base64 data is decoded, you can optionally remove `remove.from.base64` which might be in the data. The decoded data is finally persisted to the JSON document in `jsonpath.for.decoded.base64`. To horizontally scale the KStream, make sure the `input.topic.name` has multiple partitions and start another jvm with the same configuration properties file.

## Execution
Run the `base64-json-decoder-0.1-jar-with-dependencies.jar` with Java 8.

```
java -jar base64-json-decoder-0.1-jar-with-dependencies.jar dev.properties
```


## Test
Create input/output/error topics
```
$ kafka-topics --bootstrap-server big-host-1.datadisorder.dev:9092 --create --topic base64-encoded --replication-factor 1 --partitions 3 --command-config configuration/dev.properties

$ kafka-topics --bootstrap-server big-host-1.datadisorder.dev:9092 --create --topic base64-decoded --replication-factor 1 --partitions 3 --command-config configuration/dev.properties
```

or

```
PROPERTIES=configuration/dev.properties
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

Push the sample messages
```
jq -rc . test/encoded-SampleData.json | kafka-console-producer --bootstrap-server big-host-1.datadisorder.dev:9092 --topic base64-encoded
```

Validate the messages were parsed using the Confluent Control Center or with kafkacat

```
kafkacat -F configuration/kafkacat.properties -b  big-host-1.datadisorder.dev:9092 -C -t base64-decoded
```
