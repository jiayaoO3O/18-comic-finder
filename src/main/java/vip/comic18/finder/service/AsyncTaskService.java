package vip.comic18.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import vip.comic18.finder.entity.ChapterEntity;
import vip.comic18.finder.entity.PhotoEntity;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Created by jiayao on 2021/2/17.
 */
@Service
@Slf4j
public class AsyncTaskService {

    @Value("${comic.proxy.host}")
    private String proxyHost;

    @Value("${comic.proxy.port}")
    private int proxyPort;

    @Value("${comic.download.cookie}")
    private String cookie;

    /**
     * @param body          html请求的结果信息
     * @param comicHomePage 漫画主页
     * @return 章节列表
     */
    @Async
    public Future<List<ChapterEntity>> getChapterInfo(String body, String comicHomePage) {
        List<ChapterEntity> chapterEntities = new ArrayList<>();
        String host = "https://" + StrUtil.subBetween(comicHomePage, "//", "/");
        body = StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>");
        String[] chapters = StrUtil.subBetweenAll(body, "<a ", "</li>");
        for(String chapter : chapters) {
            ChapterEntity chapterEntity = new ChapterEntity();
            chapterEntity.setUrl(host + StrUtil.subBetween(chapter, "href=\"", "\""));
            chapter = StrUtil.removeAll(chapter, '\n', '\r');
            chapter = StrUtil.subAfter(chapter, "<li", false);
            String[] nameAndDate = StrUtil.subBetweenAll(chapter, ">", "<");
            chapterEntity.setName(StrUtil.trim(StrUtil.replaceChars(nameAndDate[ 0 ], new char[]{'/', '\\'}, StrUtil.DASHED)));
            chapterEntity.setUpdatedAt(DateUtil.parse(nameAndDate[ nameAndDate.length - 1 ]));
            chapterEntities.add(chapterEntity);
            log.info(chapterEntity.toString());
        }
        return new AsyncResult<>(chapterEntities);
    }

    /**
     * 获取该章节的图片信息
     *
     * @param chapterEntity 章节信息
     * @return 图片列表
     */
    @Async
    public Future<List<PhotoEntity>> getPhotoInfo(ChapterEntity chapterEntity) {
        List<PhotoEntity> photoEntities = new ArrayList<>();
        HttpResponse httpResponse = null;
        while(httpResponse == null) {
            ThreadUtil.sleep(RandomUtil.randomInt(2) * 1000L);
            try {
                httpResponse = HttpUtil.createPost(chapterEntity.getUrl()).cookie(cookie).setHttpProxy(proxyHost, proxyPort).execute();
            } catch(Exception e) {
                log.error("getPhotoInfo->获取图片信息失败:[{}]", e.getLocalizedMessage(), e);
            }
        }
        String body = httpResponse.body();
        body = StrUtil.subBetween(body, "<div class=\"row thumb-overlay-albums\" style=\"\">", "<div class=\"tab-content");
        String[] photos = StrUtil.subBetweenAll(body, "data-original=\"", "\" class=");
        for(String photo : photos) {
            PhotoEntity photoEntity = new PhotoEntity();
            photo = StrUtil.removeAll(photo, " id=\"");
            String[] urlAndName = StrUtil.split(photo, "\"");
            photoEntity.setUrl(StrUtil.trim(urlAndName[ 0 ]));
            photoEntity.setName(StrUtil.trim(urlAndName[ 1 ]));
            photoEntities.add(photoEntity);
            log.info(StrUtil.format("chapter:[{}]-photo:[{}]-url:[{}]"), chapterEntity.getName(), photoEntity.getName(), photoEntity.getUrl());
        }
        return new AsyncResult<>(photoEntities);
    }

    @Async
    public Future<BufferedImage> getImage(String url) {
        HttpResponse httpResponse = null;
        BufferedImage bufferedImage = null;
        while(httpResponse == null || bufferedImage == null) {
            try {
                ThreadUtil.sleep(RandomUtil.randomInt(2) * 1000L);
                httpResponse = HttpUtil.createPost(url).cookie(cookie).setHttpProxy(proxyHost, proxyPort).execute();
                bufferedImage = ImgUtil.read(httpResponse.bodyStream());
            } catch(Exception e) {
                log.error("getImage->下载图片失败:[{}]", e.getLocalizedMessage(), e);
            }
        }
        log.info("getImage->获取图片:[{}]成功", url);
        return new AsyncResult<>(bufferedImage);
    }


    /**
     * 对反爬虫照片进行重新排序
     * 禁漫天堂对2020-10-27之后的漫画都进行了反爬虫设置,图片顺序会被打乱,需要进行重新切割再组合
     * 例如https://cdn-msp.18comic.org/media/photos/235900/00028.jpg?v=1613363352
     *
     * @param bufferedImage 需要被切割重排的照片
     * @return 重新排序后的照片
     */
    @Async
    public Future<BufferedImage> reverseImage(BufferedImage bufferedImage) {
        int height = bufferedImage.getHeight();
        int width = bufferedImage.getWidth();
        int preImgHeight = height / 10;
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        for(int i = 0; i < 10; i++) {
            BufferedImage subimage = bufferedImage.getSubimage(0, i * preImgHeight, width, preImgHeight);
            graphics.drawImage(subimage, null, 0, height - (i + 1) * preImgHeight);
        }
        return new AsyncResult<>(result);
    }

    @Async
    public void saveImage(String path, BufferedImage bufferedImage) {
        try {
            ImageIO.write(bufferedImage, "jpg", FileUtil.file(path));
            log.info("saveImage->保存图片:[{}]成功", path);
        } catch(IOException e) {
            log.error("saveImage->{}", e.getLocalizedMessage(), e);
        }
    }

    @Async
    public void saveImage(HttpRequest httpRequest, File photoFile) {
        HttpResponse httpResponse = null;
        while(httpResponse == null) {
            try {
                ThreadUtil.sleep(RandomUtil.randomInt(2) * 1000L);
                httpResponse = httpRequest.cookie(cookie).setHttpProxy(proxyHost, proxyPort).execute();
                log.info("getImage->获取图片:[{}]成功", httpRequest.getUrl());
            } catch(Exception e) {
                log.error("saveImage->下载图片失败:[{}]", e.getLocalizedMessage(), e);
            }
        }
        httpResponse.writeBody(photoFile);
        log.info("saveImage->保存图片:[{}]成功", photoFile.getPath());
    }
}
