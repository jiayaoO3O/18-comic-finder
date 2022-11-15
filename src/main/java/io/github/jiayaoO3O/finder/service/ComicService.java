package io.github.jiayaoO3O.finder.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import io.github.jiayaoO3O.finder.entity.ChapterEntity;
import io.github.jiayaoO3O.finder.entity.PhotoEntity;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class ComicService {
    private static final Log log = Log.get();

    @Inject
    TaskService taskService;


    public void processComic(String homePage) {
        //如果网页中存在photo字段, 说明传入的链接是某个章节, 而不是漫画首页, 此时需要将photo换成album再访问, 禁漫天堂会自动重定向到该漫画的首页.
        String url = StrUtil.replace(homePage, "photo", "album");
        taskService.post(url)
                .subscribe()
                .with(this.homePageConsumer(homePage));
    }

    private Consumer<HttpResponse<Buffer>> homePageConsumer(String url) {
        return response -> {
            var body = response.bodyAsString();
            var title = taskService.getTitle(body);
            var chapterEntitiesUni = taskService.processChapterInfo(body, url);
            chapterEntitiesUni.subscribe()
                    .with(this.photoEntitiesConsumer(title));
        };
    }

    private Consumer<List<ChapterEntity>> photoEntitiesConsumer(String title) {
        return chapterEntities -> {
            var chapterEntityUniMap = taskService.processPhotoInfo(chapterEntities);
            for(var chapterEntityUniEntry : chapterEntityUniMap.entrySet()) {
                var chapterEntity = chapterEntityUniEntry.getKey();
                var photoEntitiesUni = chapterEntityUniEntry.getValue();
                var chapterDirUni = taskService.processChapterDir(title, chapterEntity);
                chapterDirUni.subscribe()
                        .with(this.chapterDirConsumer(chapterEntity, photoEntitiesUni));
            }
        };
    }

    private Consumer<String> chapterDirConsumer(ChapterEntity chapterEntity, Uni<List<PhotoEntity>> photoEntitiesUni) {
        return chapterDir -> photoEntitiesUni.subscribe()
                .with(this.photoEntitiesConsumer(chapterEntity, chapterDir));
    }

    private Consumer<List<PhotoEntity>> photoEntitiesConsumer(ChapterEntity chapterEntity, String chapterDir) {
        return photoEntities -> {
            //由于后续需要用某一张图来判断整章漫画是否需要切割, 所以photoEntities不能够按顺序排列, 因为漫画开头的第一第二张图片,
            //经常是封面, 有大片留白, 对相似度算法有相当大的影响
            photoEntities = CollUtil.sort(photoEntities, Comparator.comparingInt(photo -> RandomUtil.randomInt(2048)));
            for(PhotoEntity photoEntity : photoEntities) {
                var photoPath = chapterDir + File.separatorChar + StrUtil.replace(photoEntity.name(), ".webp", ".jpg");
                var booleanUni = taskService.processPhotoExists(photoPath);
                booleanUni.subscribe()
                        .with(this.photoExistsConsumer(chapterEntity, photoEntity, photoPath));
            }
        };
    }

    private Consumer<Boolean> photoExistsConsumer(ChapterEntity chapterEntity, PhotoEntity photoEntity, String photoPath) {
        return exists -> {
            if(exists) {
                log.info("{}[{}]已存在, 跳过处理", taskService.clickPhotoCounter(false), photoPath);
            } else {
                var bufferUni = taskService.processPhotoBuffer(photoEntity);
                bufferUni.subscribe()
                        .with(this.photoBufferConsumer(chapterEntity, photoEntity, photoPath));
            }
        };
    }

    private Consumer<Buffer> photoBufferConsumer(ChapterEntity chapterEntity, PhotoEntity photoEntity, String photoPath) {
        return buffer -> taskService.processPhotoTempFile(buffer, photoEntity)
                .subscribe()
                .with(this.tempFileConsumer(chapterEntity, photoEntity, photoPath));
    }

    private Consumer<String> tempFileConsumer(ChapterEntity chapterEntity, PhotoEntity photoEntity, String photoPath) {
        return path -> {
            if(StrUtil.isNotEmpty(path)) {
                var bufferedImageUni = taskService.processPhotoReverse(path, chapterEntity, photoEntity);
                bufferedImageUni.subscribe()
                        .with(this.finalImageConsumer(photoPath));
            }
        };
    }

    private Consumer<BufferedImage> finalImageConsumer(String photoPath) {
        return bufferedImage -> taskService.write(photoPath, bufferedImage);
    }
}
