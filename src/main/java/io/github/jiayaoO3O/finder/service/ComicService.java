package io.github.jiayaoO3O.finder.service;

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

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class ComicService {
    @ConfigProperty(name = "comic.download.path")
    String downloadPath;

    @Inject
    Logger log;

    @Inject
    TaskService taskService;

    @Inject
    Vertx vertx;

    public void consume(String comicHomePage, String body) {
        var title = taskService.getTitle(body);
        var chapterEntities = taskService.getChapterInfo(body, comicHomePage);
        chapterEntities.subscribe().with(chapterEntity -> {
            var photoEntities = taskService.getPhotoInfo(chapterEntity);
            photoEntities.subscribe().with(photoEntity -> {
                var dirPath = downloadPath + File.separatorChar + title + File.separatorChar + chapterEntity.name();
                var photoPath = dirPath + File.separatorChar + photoEntity.name();
                vertx.fileSystem().exists(photoPath).subscribe().with(exists -> {
                    if(exists) {
                        log.info(StrUtil.format("{}:图片已下载,跳过:[{}]", taskService.addProcessedPhotoCount(), photoEntity));
                    } else {
                        vertx.fileSystem().mkdirs(dirPath).onFailure().invoke(e -> log.error(StrUtil.format("downloadComic->创建文件夹失败:[{}]", e.getLocalizedMessage()), e)).subscribe().with(mkdirSucceed -> {
                            if(chapterEntity.updatedAt().after(DateUtil.parse("2020-10-27"))) {
                                log.info(StrUtil.format("downloadComic->该章节:[{}]图片:[{}]需要进行反反爬虫处理", chapterEntity.name(), photoEntity.name()));
                                var bufferUni = taskService.createGet(photoEntity.url()).onItem().transform(HttpResponse::body);
                                var tempFile = taskService.getTempFile(bufferUni);
                                taskService.process(photoPath, tempFile);
                            } else {
                                taskService.getAndSaveImage(photoEntity.url(), photoPath);
                            }
                        });
                    }
                });
            });
        });
    }

    public Uni<String> getComicInfo(String homePage) {
        //如果网页中存在photo字段, 说明传入的链接是某个章节, 而不是漫画首页, 此时需要将photo换成album再访问, 禁漫天堂会自动重定向到该漫画的首页.
        homePage = StrUtil.contains(homePage, "photo") ? StrUtil.replace(homePage, "photo", "album") : homePage;
        var homePageUni = taskService.createGet(homePage);
        return homePageUni.onItem().transform(HttpResponse::bodyAsString);
    }
}
