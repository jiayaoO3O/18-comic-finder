package io.github.jiayaoO3O.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiayaoO3O.finder.entity.ChapterEntity;
import io.github.jiayaoO3O.finder.entity.PhotoEntity;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.util.Date;

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

    /**
     * @param comicHomePage 漫画首页地址
     * @param body          漫画首页的html内容
     */
    public void consume(String comicHomePage, String body) {
        var title = taskService.getTitle(body);
        var chapterEntities = taskService.getChapterInfo(body, comicHomePage);
        chapterEntities.subscribe()
                .with(chapterEntity -> consumeChapter(title, chapterEntity));
    }

    private void consumeChapter(String title, ChapterEntity chapterEntity) {
        var photoEntities = taskService.getPhotoInfo(chapterEntity);
        photoEntities.subscribe()
                .with(photoEntity -> checkPhoto(title,chapterEntity.id(), chapterEntity.updatedAt(), chapterEntity.name(), photoEntity));
    }

    private void checkPhoto(String title,int chapterId, Date chapterUpdatedAt, String chapterName, PhotoEntity photoEntity) {
        var dirPath = downloadPath + File.separatorChar + title + File.separatorChar + chapterName;
        var photoPath = dirPath + File.separatorChar + photoEntity.name();
        vertx.fileSystem()
                .exists(photoPath)
                .subscribe()
                .with(exists -> {
                    if(exists) {
                        log.info(StrUtil.format("{}:图片已下载,跳过:[{}]", taskService.clickPhotoCounter(false), photoEntity));
                    } else {
                        createDir(chapterId,chapterUpdatedAt, chapterName, photoEntity, dirPath, photoPath);
                    }
                });
    }

    private void createDir(int chapterId, Date chapterUpdatedAt, String chapterName, PhotoEntity photoEntity, String dirPath, String photoPath) {
        vertx.fileSystem()
                .mkdirs(dirPath)
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("downloadComic->创建文件夹失败:[{}]", e.getLocalizedMessage()), e))
                .subscribe()
                .with(mkdirSucceed -> consumePhoto(chapterId,chapterUpdatedAt, chapterName, photoEntity.name(), photoEntity.url(), photoPath));
    }

    private void consumePhoto(int chapterId, Date chapterUpdatedAt, String chapterName, String photoName, String photoUrl, String photoPath) {
        if(chapterUpdatedAt.after(DateUtil.parse("2020-10-27"))) {
            log.info(StrUtil.format("downloadComic->该章节:[{}]图片:[{}]需要进行反反爬虫处理", chapterName, photoName));
            var bufferUni = taskService.post(photoUrl)
                    .onItem()
                    .transform(HttpResponse::body);
            var tempFile = taskService.getTempFile(bufferUni);
            taskService.process(chapterId,photoPath, tempFile);
        } else {
            taskService.getAndSaveImage(photoUrl, photoPath);
        }
    }

    /**
     * @param homePage 漫画首页
     * @return 漫画首页返回的请求体
     */
    public Uni<String> getComicInfo(String homePage) {
        //如果网页中存在photo字段, 说明传入的链接是某个章节, 而不是漫画首页, 此时需要将photo换成album再访问, 禁漫天堂会自动重定向到该漫画的首页.
        homePage = StrUtil.replace(homePage, "photo", "album");
        var homePageUni = taskService.post(homePage);
        return homePageUni.onItem()
                .transform(HttpResponse::bodyAsString);
    }
}
