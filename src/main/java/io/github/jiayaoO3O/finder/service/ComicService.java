package io.github.jiayaoO3O.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import io.github.jiayaoO3O.finder.entity.ChapterEntity;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.util.Date;

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class ComicService {
    private static final Log log = Log.get();

    @ConfigProperty(name = "comic.download.path")
    String downloadPath;

    @Inject
    TaskService taskService;

    @Inject
    Vertx vertx;

    /**
     * @param url  漫画首页地址
     * @param body 漫画首页的html内容
     */
    public void consumeComic(String url, String body) {
        var title = taskService.getTitle(body);
        var chapterEntities = taskService.getChapterInfo(body, url);
        chapterEntities.subscribe()
                .with(chapterEntity -> consumeChapter(title, chapterEntity));
    }

    private void consumeChapter(String title, ChapterEntity chapterEntity) {
        var photoEntities = taskService.getPhotoInfo(chapterEntity);
        photoEntities.subscribe()
                .with(photoEntity -> this.checkPhotoExists(title, chapterEntity.name(), photoEntity.name())
                        .chain(exists -> this.createChapterDir(exists, title, chapterEntity.name(), photoEntity.name()))
                        .subscribe()
                        .with(result -> this.consumePhoto(chapterEntity.id(), title, chapterEntity.name(), photoEntity.name(), photoEntity.url())));
    }

    private Uni<Boolean> checkPhotoExists(String title, String chapterName, String photoName) {
        var dirPath = downloadPath + File.separatorChar + title + File.separatorChar + chapterName;
        var photoPath = dirPath + File.separatorChar + photoName;
        return vertx.fileSystem()
                .exists(photoPath);
    }

    private Uni<Void> createChapterDir(boolean exists, String title, String chapterName, String photoName) {
        var dirPath = downloadPath + File.separatorChar + title + File.separatorChar + chapterName;
        if(exists) {
            log.info(StrUtil.format("{}:图片已下载,跳过:[{}]", taskService.clickPhotoCounter(false), dirPath + File.separatorChar + photoName));
            return Uni.createFrom()
                    .voidItem();
        } else {
            return vertx.fileSystem()
                    .mkdirs(dirPath);
        }
    }

    private void consumePhoto(int chapterId, String title, String chapterName, String photoName, String photoUrl) {
        var dirPath = downloadPath + File.separatorChar + title + File.separatorChar + chapterName;
        var photoPath = dirPath + File.separatorChar + photoName;
        taskService.post(photoUrl)
                .chain(response -> Uni.createFrom()
                        .item(response.body()))
                .chain(taskService::getTempFile)
                .chain(taskService::writeTempFile)
                .subscribe()
                .with(tempFile -> taskService.writePhoto(chapterId, photoPath, tempFile));
    }

    /**
     * @param url 漫画首页
     * @return 漫画首页返回的请求体
     */
    public Uni<String> getComicInfo(String url) {
        //如果网页中存在photo字段, 说明传入的链接是某个章节, 而不是漫画首页, 此时需要将photo换成album再访问, 禁漫天堂会自动重定向到该漫画的首页.
        url = StrUtil.replace(url, "photo", "album");
        return taskService.post(url)
                .chain(response -> Uni.createFrom()
                        .item(response.bodyAsString()));
    }
}
