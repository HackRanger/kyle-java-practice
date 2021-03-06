package idv.kyle.practice.spark.streaming;

import idv.kyle.practice.spark.streaming.streams.ReadLines;
import idv.kyle.practice.spark.streaming.streams.ReduceLines;
import idv.kyle.practice.spark.streaming.streams.RequestQueryProxy;
import idv.kyle.practice.spark.streaming.streams.ResponseFromQueryProxy;
import idv.kyle.practice.spark.streaming.streams.WriteResultToES;

import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import kafka.serializer.StringDecoder;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.SparkConf;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Usage: ./bin/spark-submit --class
 * idv.kyle.practice.spark.streaming.JavaKafkaWordCount
 * /root/StormPractice-0.0.1-jar-with-dependencies.jar config.properties
 * 
 */

public class FromKafkaToESSyncReduce12NoWAL {
  private static final Logger LOG = LoggerFactory
      .getLogger(FromKafkaToESSyncReduce12NoWAL.class);

  public static void main(String[] args) throws Exception {
    Properties prop = new Properties();
    Path pt = new Path(ConstantUtil.propertiesFileName);
    FileSystem fs = FileSystem.get(new Configuration());
    prop.load(new InputStreamReader(fs.open(pt)));

    String zkHosts = prop.getProperty("zookeeper.host");
    String kafkaGroup = prop.getProperty("kafka.group");
    String kafkaTopics = prop.getProperty("kafka.topics");
    String threadNumber = prop.getProperty("spark.kafka.thread.num");
    String esIndex = prop.getProperty("es.index");
    String esNodes = prop.getProperty("es.nodes");
    String checkpointEnabled = prop.getProperty("spark.checkpoint.enabled");
    String checkpointDirectory =
        prop.getProperty("spark.checkpoint.directory");
    int batchDuration =
        Integer.parseInt(prop.getProperty("spark.stream.batch.duration.ms"));
    
    LOG.info("read properties: zkHosts=" + zkHosts + ", kafkaTopics=" + kafkaTopics + ", esIndex=" + esIndex);

    SparkConf sparkConf =
        new SparkConf().setAppName("FromKafkaToES-SyncReduce12NoWAL");
    sparkConf.set("es.index.auto.create", "true");
    sparkConf.set("es.nodes", esNodes);
    LOG.info("es.index.auto.create set to "
        + sparkConf.get("es.index.auto.create") + ", batchDuration is "
        + batchDuration);
    JavaStreamingContext jssc =
        new JavaStreamingContext(sparkConf, new Duration(batchDuration));

    if ("true".equals(checkpointEnabled)) {
      LOG.info("enable checkpoint to directory " + checkpointDirectory);
      jssc.checkpoint(checkpointDirectory);
    }

    int numThreads = Integer.parseInt(threadNumber);
    Map<String, Integer> topicMap = new HashMap<String, Integer>();
    String[] topics = kafkaTopics.split(",");
    for (String topic : topics) {
      topicMap.put(topic, numThreads);
    }

    Map<String, String> kafkaParams = new HashMap<String, String>();
    kafkaParams.put("zookeeper.connect", zkHosts);
    kafkaParams.put("group.id", kafkaGroup);

    JavaPairReceiverInputDStream<String, String> messages =
        KafkaUtils.createStream(jssc, String.class, String.class,
            StringDecoder.class, StringDecoder.class, kafkaParams, topicMap,
            StorageLevel.MEMORY_AND_DISK_SER());

    JavaDStream<String> lines = messages.map(new ReadLines());
    JavaDStream<String> streams = lines.reduce(new ReduceLines());
    JavaDStream<String> queryResults = streams.flatMap(new RequestQueryProxy());

    JavaDStream<Map<String, String>> queryResult =
        queryResults.map(new ResponseFromQueryProxy());

    queryResult.foreach(new WriteResultToES());

    jssc.start();
    jssc.awaitTermination();
  }
}
