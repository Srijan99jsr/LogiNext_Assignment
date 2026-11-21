package com.ecom.product.web;

import com.ecom.product.model.Product;
import com.ecom.product.service.ImageStorageService;
import com.ecom.product.service.ProductService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService products;
    private final ImageStorageService images;

    public ProductController(ProductService products, ImageStorageService images) {
        this.products = products;
        this.images = images;
    }

    @GetMapping
    public List<Product> all() { return products.findAll(); }

    @GetMapping("/{id}")
    public Product one(@PathVariable Long id) { return products.findById(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Product create(@Valid @RequestBody Product p) { return products.create(p); }

    @PutMapping("/{id}")
    public Product update(@PathVariable Long id, @Valid @RequestBody Product p) { return products.update(id, p); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { products.delete(id); }

    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Product uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        products.findById(id); // 404 if the product does not exist
        return products.setImageKey(id, images.upload(id, file));
    }

    @GetMapping("/{id}/image-url")
    public Map<String, String> imageUrl(@PathVariable Long id) {
        Product p = products.findById(id);
        if (p.getImageKey() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No image");
        return Map.of("url", images.presignedUrl(p.getImageKey()));
    }
}
