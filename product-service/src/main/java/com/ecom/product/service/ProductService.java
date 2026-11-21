package com.ecom.product.service;

import com.ecom.product.model.Product;
import com.ecom.product.repo.ProductRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private final ProductRepository repo;

    public ProductService(ProductRepository repo) {
        this.repo = repo;
    }

    /** Catalogue reads are the hottest path during a sale, so they are served from Redis after the first hit. */
    @Cacheable(cacheNames = "productList")
    public List<Product> findAll() {
        log.info("Cache miss: loading all products from Aurora");
        return repo.findAll();
    }

    @Cacheable(cacheNames = "product", key = "#id")
    public Product findById(Long id) {
        log.info("Cache miss: loading product {} from Aurora", id);
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    @CacheEvict(cacheNames = "productList", allEntries = true)
    public Product create(Product p) {
        p.setId(null);
        return repo.save(p);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "product", key = "#id"),
            @CacheEvict(cacheNames = "productList", allEntries = true)})
    public Product update(Long id, Product p) {
        Product existing = load(id);
        existing.setName(p.getName());
        existing.setDescription(p.getDescription());
        existing.setPrice(p.getPrice());
        existing.setStock(p.getStock());
        return repo.save(existing);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "product", key = "#id"),
            @CacheEvict(cacheNames = "productList", allEntries = true)})
    public Product setImageKey(Long id, String key) {
        Product existing = load(id);
        existing.setImageKey(key);
        return repo.save(existing);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "product", key = "#id"),
            @CacheEvict(cacheNames = "productList", allEntries = true)})
    public void delete(Long id) {
        repo.deleteById(id);
    }

    private Product load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }
}
