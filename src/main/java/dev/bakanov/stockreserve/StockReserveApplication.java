package dev.bakanov.stockreserve;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class StockReserveApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockReserveApplication.class, args);
    }
}
