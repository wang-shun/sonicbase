package com.sonicbase.queue;

import com.sonicbase.util.JsonDict;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class LocalMessageQueueConsumer implements MessageQueueConsumer {

  private static org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(MessageQueueConsumer.class);

  private KafkaConsumer<String, String> consumer;
  private boolean shutdown;

  public void shutdown() {
    this.shutdown = true;
  }

  @Override
  public void init(String cluster, String jsonConfig, String jsonQueueConfig) {
  }

  @Override
  public List<Message> receive() {
    List<Message> ret = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Message message = new Message();
      if (LocalMessageQueueProducer.queue.size() != 0) {
        String msg = LocalMessageQueueProducer.queue.remove(0);
        message.setBody(msg);
        ret.add(message);
      }
      else {
        break;
      }
    }
    return ret;
  }

  @Override
  public void acknowledgeMessage(Message message) {
  }

}
