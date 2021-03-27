package vip.comic18.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class ComicService {
    @ConfigProperty(name = "comic.download.path")
    String downloadPath;

    @ConfigProperty(name = "quarkus.log.handler.file.\"INFO_LOG\".path")
    String logPath;

    @Inject
    Logger log;

    @Inject
    TaskService taskService;

    @Inject
    Vertx vertx;

    public void consume(String comicHomePage, String body) {
        String title = taskService.getTitle(body);
        var chapterEntities = taskService.getChapterInfo(body, comicHomePage);
        chapterEntities.subscribe().with(chapterEntity -> {
            var photoEntities = taskService.getPhotoInfo(chapterEntity);
            photoEntities.subscribe().with(photo -> {
                String dirPath = downloadPath + File.separatorChar + title + File.separatorChar + chapterEntity.getName();
                String photoPath = dirPath + File.separatorChar + photo.getName();
                vertx.fileSystem().exists(photoPath).subscribe().with(exists -> {
                    if(exists) {
                        log.info(StrUtil.format("downloadComic->图片已下载,跳过:[{}]", photo));
                    } else {
                        vertx.fileSystem().mkdirs(dirPath).onFailure().invoke(e -> log.error(StrUtil.format("downloadComic->创建文件夹失败:[{}]", e.getLocalizedMessage()), e)).subscribe().with(mkdirSucceed -> {
                            if(chapterEntity.getUpdatedAt().after(DateUtil.parse("2020-10-27"))) {
                                log.info(StrUtil.format("downloadComic->该章节:[{}]图片:[{}]需要进行反反爬虫处理", chapterEntity.getName(), photo.getName()));
                                var bufferUni = taskService.get(photo.getUrl()).onItem().transform(HttpResponse::body);
                                var tempFile = taskService.getTempFile(bufferUni);
                                taskService.process(photoPath, tempFile);
                            } else {
                                taskService.getAndSaveImage(photo.getUrl(), photoPath);
                            }
                        });
                    }
                });
            });
        });
    }


    public Uni<String> getComicInfo(String comicHomePage) {
        comicHomePage = StrUtil.contains(comicHomePage, "photo") ? StrUtil.replace(comicHomePage, "photo", "album") : comicHomePage;
        var homePageUni = taskService.get(comicHomePage);
        return homePageUni.onItem().transform(HttpResponse::bodyAsString);
    }

    public boolean exit() {
        var path = Path.of(logPath);
        var compareTo = 0;
        try {
            var lastModifiedTime = Files.getLastModifiedTime(path);
            compareTo = lastModifiedTime.compareTo(FileTime.from(DateUtil.toInstant(DateUtil.offsetSecond(DateUtil.date(), -10))));
        } catch(IOException e) {
            log.error(StrUtil.format("exit->读取日志错误:[{}]", e.getLocalizedMessage()), e);
        }
        return compareTo < 0;
    }


}
