package in.ac.iitm.evenhand.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The instructor's local application.
 *
 * <p>It runs on his own machine as a process bound to the loopback interface, or in a
 * container. There is no hosted deployment and no external API: marks and identities
 * never leave the machine they are loaded on, which is a condition of our obtaining
 * the data at all. The binding is set in {@code application.properties} and asserted
 * by a test rather than left to a reviewer to notice.
 */
@SpringBootApplication
public class EvenHandApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvenHandApplication.class, args);
    }
}
