package vip.comic18.finder.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Created by jiayao on 2021/2/16.
 */
@Component
@Slf4j
public class ComicRunner implements CommandLineRunner {
    @Override
    public void run(String... args) {
        log.info("注意身体,适度看漫");
    }
}
