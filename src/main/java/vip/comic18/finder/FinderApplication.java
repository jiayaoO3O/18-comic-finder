package vip.comic18.finder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class FinderApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinderApplication.class, args);
	}

}
