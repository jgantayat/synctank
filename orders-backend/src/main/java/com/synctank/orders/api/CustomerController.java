package com.synctank.orders.api;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    public record CustomerResponse(Long id, String name, String email) {}

    @GetMapping("/{id}")
    public CustomerResponse getCustomer(@PathVariable Long id) {
        return new CustomerResponse(id, "Asha Rao", "asha@example.com");
    }
}