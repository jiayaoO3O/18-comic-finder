package vip.comic18.finder.service;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.thread.ThreadUtil;
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
import java.util.concurrent.CompletableFuture;
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
    private Integer proxyPort;

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
        if(StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>") == null) {
            //说明该漫画是单章漫画,没有区分章节,例如王者荣耀图鉴类型的https://18comic.vip/album/203961
            String url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", ">開始閱讀<"), "href=\"", "/\"");
            String name = StrUtil.trim(StrUtil.replaceChars(StrUtil.subBetween(body, "<div itemprop=\"name\" class=\"pull-left\">\n", "\n</div>"), new char[]{'/', '\\'}, StrUtil.DASHED));
            DateTime updatedAt = DateUtil.parse(StrUtil.subBetween(StrUtil.subBetween(body, "itemprop=\"datePublished\"", "上架日期"), "content=\"", "\""));
            ChapterEntity chapterEntity = new ChapterEntity();
            chapterEntity.setUrl(host + url);
            chapterEntity.setName(name);
            chapterEntity.setUpdatedAt(updatedAt);
            chapterEntities.add(chapterEntity);
            log.info(chapterEntity.toString());
            return CompletableFuture.completedFuture(chapterEntities);
        }
        body = StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>");
        String[] chapters = StrUtil.subBetweenAll(body, "<a ", "</li>");
        for(String chapter : chapters) {
            if(comicHomePage.contains("photo") && !comicHomePage.equals(host + StrUtil.subBetween(chapter, "href=\"", "\""))) {
                continue;
            }
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
            ThreadUtil.sleep(2000L);
            try {
                httpResponse = this.createPost(chapterEntity.getUrl()).execute();
            } catch(Exception e) {
                log.error("getPhotoInfo->获取图片信息失败,正在重试:[{}]", e.getLocalizedMessage(), e);
            }
        }
        String body = httpResponse.body();
        body = StrUtil.subBetween(body, "<div class=\"row thumb-overlay-albums\" style=\"\">", "<div class=\"tab-content");
        String[] photos = StrUtil.subBetweenAll(body, "data-original=\"", "\" class=");
        for(String photo : photos) {
            if(StrUtil.contains(photo, ".jpg")) {
                photo = StrUtil.removeAll(photo, " id=\"");
                String[] urlAndName = StrUtil.split(photo, "\"");
                PhotoEntity photoEntity = new PhotoEntity();
                photoEntity.setUrl(StrUtil.trim(urlAndName[ 0 ]));
                photoEntity.setName(StrUtil.trim(urlAndName[ 1 ]));
                photoEntities.add(photoEntity);
                //log.info(StrUtil.format("chapter:[{}]-photo:[{}]-url:[{}]"), chapterEntity.getName(), photoEntity.getName(), photoEntity.getUrl());
            }
        }
        return new AsyncResult<>(photoEntities);
    }

    @Async
    public Future<BufferedImage> getImage(String url) {
        HttpResponse httpResponse = null;
        BufferedImage bufferedImage = null;
        while(httpResponse == null || bufferedImage == null) {
            try {
                ThreadUtil.sleep(2000L);
                httpResponse = this.createPost(url).execute();
                bufferedImage = ImgUtil.read(httpResponse.bodyStream());
            } catch(Exception e) {
                log.error("getImage->下载图片失败,正在重试:[{}]", e.getLocalizedMessage(), e);
            }
        }
        log.info("getImage->成功下载图片:[{}]", url);
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
    public void saveImage(File photoFile, BufferedImage bufferedImage) {
        boolean isWrite = false;
        while(!isWrite) {
            try {
                isWrite = ImageIO.write(bufferedImage, "jpg", photoFile);
                log.info("saveImage->成功保存图片:[{}]", photoFile.getPath());
            } catch(IOException e) {
                log.error("saveImage->{}", e.getLocalizedMessage(), e);
                ThreadUtil.sleep(2000L);
            }
        }
    }

    @Async
    public void getAndSaveImage(String url, File photoFile) {
        HttpResponse httpResponse = null;
        long writeResult = 0L;
        while(httpResponse == null || writeResult == 0L) {
            try {
                ThreadUtil.sleep(2000L);
                httpResponse = this.createPost(url).execute();
                log.info("getImage->成功获取图片:[{}]", url);
                writeResult = httpResponse.writeBody(photoFile);
                log.info("saveImage->成功保存图片:[{}]", photoFile.getPath());
            } catch(Exception e) {
                log.error("getAndSaveImage->下载图片失败,正在重试:[{}][{}]", photoFile.getPath(), e.getLocalizedMessage(), e);
            }
        }
    }

    public HttpRequest createPost(String url) {
        HttpRequest post = HttpUtil.createPost(url);
        if((proxyHost != null && proxyPort != null)) {
            post.setHttpProxy(proxyHost, proxyPort);
        }
        if(cookie != null) {
            post.cookie(cookie);
        }
        return post;
    }
}
