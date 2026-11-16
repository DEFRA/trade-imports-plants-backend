package uk.gov.defra.trade.imports.plants.configuration;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ConnectionPoolSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import javax.net.ssl.SSLContext;
import java.util.List;
import java.util.concurrent.TimeUnit;
import uk.gov.defra.trade.imports.plants.configuration.tls.TrustStoreConfiguration;

@Configuration
@Slf4j
public class MongoConfig {

  @Bean
  ConnectionPoolSettings connectionPoolSettings(
      @Value("${spring.data.mongodb.connection-pool.min-size}") int minPoolSize,
      @Value("${spring.data.mongodb.connection-pool.max-size}") int maxPoolSize,
      @Value("${spring.data.mongodb.connection-pool.max-wait-time-ms}") int maxWaitTimeMs,
      @Value("${spring.data.mongodb.connection-pool.max-connection-idle-time-ms}")
          int maxIdleTimeMs) {

    return ConnectionPoolSettings.builder()
        .minSize(minPoolSize)
        .maxSize(maxPoolSize)
        .maxWaitTime(maxWaitTimeMs, TimeUnit.MILLISECONDS)
        .maxConnectionIdleTime(maxIdleTimeMs, TimeUnit.MILLISECONDS)
        .build();
  }

  @Bean
  MongoClientSettings mongoClientSettings(
      @Value("${spring.data.mongodb.ssl.enabled}") boolean sslEnabled,
      @Value("${spring.data.mongodb.uri}") String mongoUri,
      @Value("${spring.data.mongodb.read-preference}") ReadPreference readPreference,
      @Value("${spring.data.mongodb.write-concern}") WriteConcern writeConcern,
      TrustStoreConfiguration trustStoreConfiguration,
      ConnectionPoolSettings connectionPoolSettings) {

      MongoClientSettings.Builder builder = MongoClientSettings.builder()
          .applyConnectionString(new ConnectionString(mongoUri))
          .applyToConnectionPoolSettings(bdr -> bdr.applySettings(connectionPoolSettings))
          .readPreference(readPreference)
          .writeConcern(writeConcern);
      
      if (sslEnabled) {
          SSLContext sslContext = trustStoreConfiguration.customSslContext();
          builder.applyToSslSettings(bdr -> bdr.context(sslContext));
          log.info("MongoDB SSL configured with the trust store SSL context");
      }
      log.info("MongoDB client configuration complete");
      
    return builder.build();
  }

  @Bean
  MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(
        new LocalDateStringConverters.LocalDateToStringConverter(),
        new LocalDateStringConverters.StringToLocalDateConverter()));
  }

  @Bean
  MongoClient mongoClient(MongoClientSettings mongoClientSettings) {
      log.info("Creating MongoDB client");
    return MongoClients.create(mongoClientSettings);
  }

  @Bean
  MongoTransactionManager transactionManager(
      MongoDatabaseFactory dbFactory,
      @Value("${mongo.transaction.commit-retry.timeout-ms}") long commitRetryTimeoutMs,
      @Value("${mongo.transaction.commit-retry.backoff-ms}") long commitRetryBackoffMs) {

    return new CommitRetryingMongoTransactionManager(
        dbFactory, commitRetryTimeoutMs, commitRetryBackoffMs);
  }
}
