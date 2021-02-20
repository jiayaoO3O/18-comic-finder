package vip.comic18.finder.runner;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import vip.comic18.finder.entity.ComicEntity;
import vip.comic18.finder.service.ComicService;

import java.util.concurrent.ExecutionException;

/**
 * Created by jiayao on 2021/2/16.
 */
@Component
@Slf4j
public class ComicRunner implements CommandLineRunner {
    @Autowired
    private ComicService comicService;
    @Value("${comic.download.url}")
    private String homePage;

    @Override
    public void run(String... args) throws ExecutionException, InterruptedException {
        log.info("注意身体,适度看漫");
        if(StrUtil.isEmpty(homePage)) {
            return;
        }
        ComicEntity comicInfo = comicService.getComicInfo(homePage);
        log.info("开始下载[{}]:[{}]", comicInfo.getTitle(), homePage);
        comicService.downloadComic(comicInfo);
        log.info("下载[{}]完成", comicInfo.getTitle());
    }
}
