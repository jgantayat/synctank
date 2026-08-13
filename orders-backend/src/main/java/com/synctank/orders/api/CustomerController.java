package com.synctank.orders.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    public record CustomerResponse(Long id, String name, String email) {}

    @GetMapping(value="/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CustomerResponse getCustomer(@PathVariable Long id) {
        return new CustomerResponse(id, "Asha Rao", "asha@example.com");
    }
}