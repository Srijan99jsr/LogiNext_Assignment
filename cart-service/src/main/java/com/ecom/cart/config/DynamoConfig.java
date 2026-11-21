package com.ecom.cart.config;

import com.ecom.cart.model.CartItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Credentials come from the ECS task IAM role; no keys in code or config. */
@Configuration
public class DynamoConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(@Value("${app.aws-region}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public DynamoDbTable<CartItem> cartTable(DynamoDbClient client, @Value("${app.cart-table}") String tableName) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build()
                .table(tableName, TableSchema.fromBean(CartItem.class));
    }
}
