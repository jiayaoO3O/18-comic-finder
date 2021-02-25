package vip.comic18.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.comic18.finder.entity.ChapterEntity;
import vip.comic18.finder.entity.ComicEntity;
import vip.comic18.finder.entity.PhotoEntity;

import java.io.File;
import java.util.List;

/**
 * Created by jiayao on 2021/2/16.
 */
@Service
@Slf4j
public class ComicService {
    @Autowired
    private AsyncTaskService taskService;

    @Value("${comic.download.path}")
    private String downloadPath;


    /**
     * 下载漫画到本地
     * 漫画会在下载路径下按照漫画名-章节名-图片名归类
     *
     * @param comicEntity 漫画信息
     */
    public void downloadComic(ComicEntity comicEntity) {
        String title = comicEntity.getTitle();
        if(downloadPath == null) {
            log.error("downloadComic->下载路径downloadPath错误,无法下载文件,请确保配置文件中下载路径正确");
            return;
        }
        for(ChapterEntity chapter : comicEntity.getChapters()) {
            List<PhotoEntity> photos = chapter.getPhotos();
            for(PhotoEntity photo : photos) {
                String photoPath = downloadPath + File.separatorChar + title + File.separatorChar + chapter.getName() + File.separatorChar + photo.getName();
                File photoFile = FileUtil.file(photoPath);
                if(photoFile.exists()) {
                    log.info("downloadComic->图片已下载,跳过[{}]", photoFile.getPath());
                    continue;
                }
                if(chapter.getUpdatedAt().after(DateUtil.parse("2020-10-27"))) {
                    log.info("downloadComic->该章节:[{}]图片:[{}]需要进行反反爬虫处理", chapter.getName(), photo.getName());
                    taskService.getImage(photo.getUrl()).thenApplyAsync(taskService::reverseImage).thenAcceptAsync(image -> taskService.saveImage(photoFile, image.join()));
                } else {
                    taskService.getAndSaveImage(photo.getUrl(), photoFile);
                }
            }
        }

    }

    /**
     * 获取整本漫画的所有信息
     *
     * @param comicHomePage 漫画的主页
     * @return 漫画信息
     */
    public ComicEntity getComicInfo(String comicHomePage) {
        ComicEntity comicEntity = new ComicEntity();
        HttpResponse httpResponse = null;
        while(httpResponse == null) {
            try {
                if(StrUtil.contains(comicHomePage, "photo")) {
                    httpResponse = taskService.createPost(StrUtil.replace(comicHomePage, "photo", "album")).setFollowRedirects(true).execute();
                } else {
                    httpResponse = taskService.createPost(comicHomePage).execute();
                }
            } catch(Exception e) {
                log.error("getComicInfo->漫画首页信息失败,正在重试:[{}]", e.getLocalizedMessage(), e);
            }
        }
        String body = httpResponse.body();
        String title = StrUtil.subBetween(body, "<h1>", "</h1>");
        title = StrUtil.replaceChars(title, new char[]{'/', '\\'}, StrUtil.DASHED);
        title = StrUtil.trim(title);
        comicEntity.setTitle(title);
        comicEntity.setChapters(taskService.getChapterInfo(body, comicHomePage).thenApplyAsync(chapterEntities -> {
            chapterEntities.forEach(chapterEntity -> chapterEntity.setPhotos(taskService.getPhotoInfo(chapterEntity).join()));
            return chapterEntities;
        }).join());
        return comicEntity;
    }
}
