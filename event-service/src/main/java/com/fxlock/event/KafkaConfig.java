package com.fxlock.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig 
{
  @Bean
  NewTopic rates() 
  {
    return TopicBuilder.name("fx-rate-updated").partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic settlements() 
  {
    return TopicBuilder.name("fx-settlement-completed").partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic alerts() 
  {
    return TopicBuilder.name("fx-system-alert").partitions(3).replicas(1).build();
  }

  @Bean
  DefaultErrorHandler consumerErrors() 
  {
    return new DefaultErrorHandler(new FixedBackOff(2000, FixedBackOff.UNLIMITED_ATTEMPTS));
  }
}




