package crg.api.external;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class ExternalApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExternalApplication.class, args);
	}

//	@Bean
//	public CommandLineRunner startup(BCryptPasswordEncoder encoder) {
//		return args -> {
//			var password = encoder.encode("doukoure2711");
//			System.out.println(password);
//		};
//	}

}
