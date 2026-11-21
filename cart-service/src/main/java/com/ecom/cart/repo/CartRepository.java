package com.ecom.cart.repo;

import com.ecom.cart.model.CartItem;
import java.util.List;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

@Repository
public class CartRepository {

    private final DynamoDbTable<CartItem> table;

    public CartRepository(DynamoDbTable<CartItem> table) {
        this.table = table;
    }

    /** Single-partition Query: fast and cheap no matter how many users exist. */
    public List<CartItem> findByUser(String userId) {
        return table.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()))
                .items().stream().toList();
    }

    public void save(CartItem item) {
        table.putItem(item);
    }

    public void delete(String userId, String productId) {
        table.deleteItem(Key.builder().partitionValue(userId).sortValue(productId).build());
    }
}
