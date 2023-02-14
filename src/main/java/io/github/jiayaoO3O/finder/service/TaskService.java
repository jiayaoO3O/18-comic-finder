package io.github.jiayaoO3O.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.MD5;
import cn.hutool.log.Log;
import io.github.jiayaoO3O.finder.entity.ChapterEntity;
import io.github.jiayaoO3O.finder.entity.PhotoEntity;
import io.quarkus.runtime.Quarkus;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class TaskService {
    private static final Log log = Log.get();

    @ConfigProperty(name = "comic.request.cookie")
    Optional<String> cookie;

    @ConfigProperty(name = "quarkus.log.file.path")
    String logPath;

    @ConfigProperty(name = "comic.download.path")
    String downloadPath;

    @ConfigProperty(name = "comic.domain.list")
    List<String> domainList;

    static String domain;

    LongAdder domainAdder = new LongAdder();

    @Inject
    Vertx vertx;

    @Inject
    @Named("webClient")
    WebClient webClient;

    LongAdder pendingPhotoCount = new LongAdder();

    LongAdder processedPhotoCount = new LongAdder();

    private static final MD5 MD5 = cn.hutool.crypto.digest.MD5.create();

    private static final int[] rule = new int[]{2, 4, 6, 8, 10, 12, 14, 16, 18, 20};

    public Uni<List<ChapterEntity>> processChapterInfo(String body, String homePage) {
        List<ChapterEntity> chapterEntities = new ArrayList<>();
        //var host = "https://" + StrUtil.subBetween(homePage, "//", "/");
        var host = "https://" + domain;
        var isSingleChapter = StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>") == null;
        if(isSingleChapter) {
            //说明该漫画是单章漫画,没有区分章节,例如王者荣耀图鉴类型的https://18comic.vip/album/203961
            var url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", "開始閱讀"), "href=\"", "/\"");
            if(StrUtil.isEmpty(url)) {
                url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", "開始閱讀"), "href=\"", "\"");
            }
            //获取章节名称
            var name = StrUtil.removeAny(StrUtil.splitTrim(this.removeIllegalCharacter(StrUtil.subBetween(body, "<h1>", "</h1>")), " ")
                    .toString(), "[", "]", ",");
            while(StrUtil.endWith(name, '.')) {
                name = StrUtil.removeSuffix(name, ".");
            }
            //获取章节id
            String id = StrUtil.subAfter(url, '/', true);
            if(StrUtil.hasEmpty(id, name, url)) {
                //对于单章漫画, 存在为空的情况直接退出程序了
                log.error(StrUtil.format("获取章节信息失败->解析漫画url/name/id为空,程序退出"));
                Quarkus.asyncExit();
            }
            var chapterEntity = new ChapterEntity(Integer.parseInt(id), name, host + url);
            chapterEntities.add(chapterEntity);
            return Uni.createFrom()
                    .item(chapterEntities);
        }
        body = StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>");
        var chapters = StrUtil.subBetweenAll(body, "<a ", "</li>");
        homePage = StrUtil.removeSuffix(homePage, "/");
        for(var chapter : chapters) {
            var url = StrUtil.subBetween(chapter, "href=\"", "\"");
            if(homePage.contains("photo") && !homePage.equals(host + url)) {
                continue;
            }
            chapter = StrUtil.removeAll(chapter, '\n', '\r');
            chapter = StrUtil.subAfter(chapter, "<li", false);
            var nameAndDate = StrUtil.subBetweenAll(chapter, ">", "<");
            var name = StrUtil.removeAny(StrUtil.splitTrim(this.removeIllegalCharacter(nameAndDate[ 0 ]), " ")
                    .toString(), "[", "]", ",");
            String id = StrUtil.subAfter(url, '/', true);
            while(StrUtil.endWith(name, '.')) {
                name = StrUtil.removeSuffix(name, ".");
            }
            if(StrUtil.hasEmpty(id, name, url)) {
                //对于多章漫画, 存在为空数据直接跳过这一章
                log.error(StrUtil.format("获取章节信息失败->解析漫画url/name/id为空,跳过本章节"));
                continue;
            }
            var chapterEntity = new ChapterEntity(Integer.parseInt(id), name, host + url);
            chapterEntities.add(chapterEntity);
        }
        return Uni.createFrom()
                .item(chapterEntities);
    }


    public Map<ChapterEntity, Uni<List<PhotoEntity>>> processPhotoInfo(List<ChapterEntity> chapterEntities) {
        Map<ChapterEntity, Uni<List<PhotoEntity>>> chapterToPhotoMap = new HashMap<>();
        for(ChapterEntity chapterEntity : chapterEntities) {
            var responseUni = this.post(chapterEntity.url());
            var photoEntitiesUni = responseUni.chain(response -> {
                var body = response.bodyAsString();
                body = StrUtil.subBetween(body, "<div class=\"row thumb-overlay-albums\">", "<div class=\"tab-content");
                var photos = StrUtil.subBetweenAll(body, "data-original=\"", "\" class=");
                List<PhotoEntity> photoEntities = new ArrayList<>();
                for(var photo : photos) {
                    if(StrUtil.containsAny(photo, ".jpg", ".webp", ".png")) {
                        photo = StrUtil.removeAll(photo, " id=\"");
                        var urlAndName = StrUtil.splitToArray(photo, "\"");
                        var photoEntity = new PhotoEntity(StrUtil.trim(urlAndName[ 1 ]), StrUtil.trim(StrUtil.replace(urlAndName[ 0 ], StrUtil.subBetween(urlAndName[ 0 ], "//", "/"), domain)));
                        photoEntities.add(photoEntity);
                        log.info(StrUtil.format("{}:chapter:[{}]-photo:[{}]-url:[{}]", this.clickPhotoCounter(true), chapterEntity.name(), photoEntity.name(), photoEntity.url()));
                    }
                }
                photoEntities = this.processPhotoPagination(body, chapterEntity, photoEntities);
                return Uni.createFrom()
                        .item(photoEntities);
            });
            chapterToPhotoMap.put(chapterEntity, photoEntitiesUni);
        }
        return chapterToPhotoMap;
    }

    private List<PhotoEntity> processPhotoPagination(String body, ChapterEntity chapterEntity, List<PhotoEntity> photoEntities) {
        //禁漫天堂网页一页最多显示300张图片, 某些漫画例如https://18comic.vip/photo/140709可能单章超过300所以需要处理分页
        var size = photoEntities.size();
        if(StrUtil.contains(body, "pagination")) {
            var pageInfo = StrUtil.subBetween(body, "<ul class=\"pagination\">", "prevnext");
            var pages = StrUtil.subBetweenAll(pageInfo, "<a href=\"", "\">");
            var pageCount = pages.length;
            while(pageCount > 0) {
                var lastPhotoEntity = photoEntities.get(photoEntities.size() - 1);
                var name = lastPhotoEntity.name();
                var url = lastPhotoEntity.url();
                for(int i = 0; i < size; i++) {
                    var oldIndex = StrUtil.subBetween(name, "photo_", ".");
                    var newIndex = StrUtil.fillBefore(Integer.parseInt(oldIndex) + 1 + "", '0', 5);
                    name = StrUtil.replace(name, oldIndex, newIndex);
                    oldIndex = StrUtil.subAfter(StrUtil.subBetween(url, "photos/", "."), "/", true);
                    newIndex = StrUtil.fillBefore(Integer.parseInt(oldIndex) + 1 + "", '0', 5);
                    url = StrUtil.replace(url, oldIndex, newIndex);
                    PhotoEntity photoEntity = new PhotoEntity(name, url);
                    photoEntities.add(photoEntity);
                    log.info(StrUtil.format("{}:chapter:[{}]-photo:[{}]-url:[{}]", this.clickPhotoCounter(true), chapterEntity.name(), photoEntity.name(), photoEntity.url()));
                }
                pageCount--;
            }
        }
        return photoEntities;
    }

    public Uni<String> processChapterDir(String title, ChapterEntity chapterEntity) {
        var chapterName = chapterEntity.name();
        var dirPath = downloadPath + File.separatorChar + title + File.separatorChar + chapterName;
        var voidUni = vertx.fileSystem()
                .mkdirs(dirPath);
        return voidUni.chain(v -> Uni.createFrom()
                .item(dirPath));
    }

    public Uni<Boolean> processPhotoExists(String photoPath) {
        return vertx.fileSystem()
                .exists(photoPath);
    }

    public Uni<Buffer> processPhotoBuffer(PhotoEntity photoEntity) {
        var photoUrl = photoEntity.url();
        return this.post(photoUrl)
                .chain(response -> Uni.createFrom()
                        .item(response.bodyAsBuffer()));
    }

    public Uni<String> processPhotoTempFile(Buffer buffer, PhotoEntity photoEntity) {
        if(buffer == null) {
            log.info("{}[{}]对应buffer为null, 跳过处理", this.clickPhotoCounter(false), photoEntity.url());
            return Uni.createFrom()
                    .item("");
        }
        var tempFile = vertx.fileSystem()
                .createTempFile(String.valueOf(System.nanoTime()), ".tmp");
        var voidUni = tempFile.chain(path -> vertx.fileSystem()
                .writeFile(path, buffer)
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("保存文件:[{}]失败:[{}]", photoEntity.url(), e.getLocalizedMessage()), e))
                .onItem()
                .transform(v -> path));
        return voidUni;
    }

    public Uni<BufferedImage> processPhotoReverse(String tempFilePath, ChapterEntity chapterEntity, PhotoEntity photoEntity) {
        BufferedImage bufferedImage = null;
        var chapterId = chapterEntity.id();
        var name = photoEntity.name();
        try(var inputStream = Files.newInputStream(Path.of(tempFilePath))) {
            bufferedImage = ImageIO.read(inputStream);
            if(bufferedImage == null) {
                log.error(StrUtil.format("临时文件读取为空:[{}]", tempFilePath));
            } else {
                bufferedImage = this.reverse(bufferedImage, chapterId, name);
            }
        } catch(IOException e) {
            log.error(StrUtil.format("反爬处理->创建newInputStream失败:[{}]", e.getLocalizedMessage()), e);
        }
        this.delete(tempFilePath);
        return Uni.createFrom()
                .item(bufferedImage);
    }

    public BufferedImage reverse(@NotNull BufferedImage bufferedImage, int chapterId, String photoName) {
        //禁漫天堂最新的切割算法, 不再固定切割成10份, 而是需要通过chapterId和photoId共同确定分割块数.
        if(chapterId <= 220971) {
            log.info("✖️:[{}]小于220971, [{}]不需要切割", chapterId, photoName);
            return bufferedImage;
        }
        int piece = 10;
        if(chapterId >= 268850) {
            String photoId = "";
            if(photoName.endsWith(".jpg")) {
                photoId = StrUtil.subBetween(photoName, "photo_", ".jpg");
            }
            if(photoName.endsWith(".webp")) {
                photoId = StrUtil.subBetween(photoName, "photo_", ".webp");
            }
            String md5 = MD5.digestHex(chapterId + photoId);
            char c = md5.charAt(md5.length() - 1);
            int mod = 10;
            if(chapterId >= 421926) {
                mod = 8;
            }
            piece = rule[ c % mod ];
        }
        return this.reverseImage(bufferedImage, chapterId, piece);
    }

    /**
     * 对反爬虫照片进行重新排序.
     * 禁漫天堂对2020-10-27之后的漫画都进行了反爬虫设置, 图片顺序会被打乱, 需要进行重新切割再组合.
     * 例如https://cdn-msp.18comic.org/media/photos/235900/00028.jpg?v=1613363352
     *
     * @param bufferedImage 待反转的图片
     * @param piece         需要切割的块数
     * @return 已处理的图片
     */
    public BufferedImage reverseImage(BufferedImage bufferedImage, int chapterId, int piece) {
        if(piece == 1) {
            return bufferedImage;
        }
        int height = bufferedImage.getHeight();
        int width = bufferedImage.getWidth();
        int preImgHeight = height / piece;
        if(preImgHeight == 0) {
            //如果分块后高度不足1像素说明不需要切割, 直接返回即可
            return bufferedImage;
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        for(int i = 0; i < piece; i++) {
            BufferedImage subImage;
            if(i == piece - 1) {
                //漫画的高度除以块数时,不一定是整数,此时漫画的第一块高度要算上剩余的像素.
                subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, height - i * preImgHeight);
                graphics.drawImage(subImage, null, 0, 0);
            } else {
                subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, preImgHeight);
                graphics.drawImage(subImage, null, 0, height - (i + 1) * preImgHeight);
            }
        }
        return result;
    }

    /**
     * @param url 发送请求的url
     * @return 请求结果
     */
    public Uni<HttpResponse<Buffer>> post(String url) {
        var request = webClient.getAbs(url)
                .port(443)
                .putHeader("Accept", "text/html,application/xhtml+xml,application/xml")
                .putHeader("Accept-Encoding", "deflate")
                .putHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
                .putHeader("Refer", url)
                .putHeader("cache-control", "no-cache")
                .putHeader("dnt", "1")
                .putHeader("upgrade-insecure-requests", " 1")
                .putHeader("pragma", " no-cache");
        //cookie.ifPresent(cook -> request.putHeader("cookie", cook));
        return request.send()
                .chain(response -> this.checkResponseStatus(url, response));
    }

    public Uni<HttpResponse<Buffer>> postRetry(String url) {
        return this.post(url)
                .onFailure()
                .recoverWithUni(response -> {
                    domainAdder.increment();
                    if(domainAdder.intValue() > domainList.size()) {
                        throw new IllegalStateException("全部域名存在访问限制!");
                    } else {
                        return this.postRetry(StrUtil.replace(url, StrUtil.subBetween(url, "//", "/"), domainList.get(domainAdder.intValue() - 1)));
                    }
                });
    }

    private Uni<HttpResponse<Buffer>> checkResponseStatus(String url, HttpResponse<Buffer> response) {
        if(StrUtil.contains(response.bodyAsString(), "休息一分鐘")) {
            log.error(StrUtil.format("发送请求[{}]->访问频率过高导致需要等待, 正在进入重试:[{}]", url, response.bodyAsString()));
            throw new IllegalStateException(response.bodyAsString());
        }
        if(StrUtil.contains(response.bodyAsString(), "Checking your browser before accessing")) {
            log.error(StrUtil.format("发送请求[{}]->发现反爬虫五秒盾, 正在进入重试:[Checking your browser before accessing]", url));
            throw new IllegalStateException("Checking your browser before accessing");
        }
        if(StrUtil.contains(response.bodyAsString(), "Please complete the security check to access")) {
            log.error(StrUtil.format("发送请求[{}]->发现cloudflare反爬虫验证, 正在进入重试:[Please complete the security check to access]", url));
            throw new IllegalStateException("Checking your browser before accessing");
        }
        if(StrUtil.contains(response.bodyAsString(), "Restricted Access")) {
            log.error(StrUtil.format("发送请求[{}]->访问受限, 正在进入重试:[Restricted Access!]", url));
            throw new IllegalStateException("Restricted Access!");
        }
        if(StrUtil.contains(response.bodyAsString(), "Cloudflare")) {
            log.error(StrUtil.format("发送请求[{}]->发现cloudflare反爬虫验证, 正在进入重试:[We are checking your browser...]", url));
            throw new IllegalStateException("We are checking your browser...");
        }
        domain = StrUtil.subBetween(url, "//", "/");
        return Uni.createFrom()
                .item(response);
    }


    public void delete(String path) {
        vertx.fileSystem()
                .delete(path)
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("🗑️:[{}]删除临时文件失败:[{}]", path, e.getLocalizedMessage()), e))
                .subscribe()
                .with(succeed -> log.info(StrUtil.format("🗑️:[{}]删除临时文件成功", path)));
    }

    /**
     * @param path          写入文件路径
     * @param bufferedImage 图片buffer
     */
    public void write(String path, BufferedImage bufferedImage) {
        if(bufferedImage == null) {
            return;
        }
        try(var outputStream = Files.newOutputStream(Path.of(path))) {
            ImageIO.write(bufferedImage, "jpg", outputStream);
            log.info(StrUtil.format("{}保存文件成功:[{}]", this.clickPhotoCounter(false), path));
        } catch(IOException e) {
            log.error(StrUtil.format("{}保存文件失败:[{}][{}]", this.clickPhotoCounter(false), path, e.getLocalizedMessage()), e);
        }
    }

    /**
     * @param body 网页的html内容.
     * @return 漫画的标题.
     */
    public String getTitle(String body) {
        String title = StrUtil.subBetween(body, "<h1>", "</h1>");
        title = this.removeIllegalCharacter(title);
        return title;
    }

    /**
     * 记录需要处理的图片数目和已经处理的图片数目.
     * pendingPhotoCount和processedPhotoCount初始值都为0.
     * 每当有一张图片需要下载, pendingPhotoCount加一;
     * 每当有一张图片已经处理完成, processedPhotoCount减一;
     * 到最后, 如果两者相加不为0, 说明还有图片未处理完成, 程序不能结束
     *
     * @param produce 是否是待处理的图片, true:图片待处理; false:照片已处理.
     * @return 返回目前待处理或者已处理的图片数目.
     */
    public String clickPhotoCounter(boolean produce) {
        if(produce) {
            this.pendingPhotoCount.increment();
            return StrUtil.format("🧻:[{}]->", this.pendingPhotoCount.longValue());
        } else {
            this.processedPhotoCount.decrement();
            return StrUtil.format("👌:[{}]->", this.processedPhotoCount.longValue());
        }
    }

    /**
     * 前台模式的退出时机检测.
     * 一个异步程序什么时候能够结束运行是不好判断的, 因为所有的处理都是异步的, 返回了并不代表就已经结束了,
     * 所以这里的解决方案是间歇性读取日志文件, 如果发现日志文件长时间没有被修改,
     * 并且pendingPhotoCount和processedPhotoCount相加为0,
     * 那就说明程序已经完成任务了, 可以停止运行了.
     *
     * @return 程序是否应该退出.
     */
    public boolean processExit() {
        var path = Path.of(logPath);
        var compareTo = 0;
        try {
            var lastModifiedTime = Files.getLastModifiedTime(path);
            compareTo = lastModifiedTime.compareTo(FileTime.from(DateUtil.toInstant(DateUtil.offsetSecond(DateUtil.date(), -60))));
        } catch(IOException e) {
            log.error(StrUtil.format("生命周期检测->读取日志错误:[{}]", e.getLocalizedMessage()), e);
        }
        return compareTo < 0;
    }

    /**
     * github action中的upload插件对含有以下几种字符的文件路径视为非法路径, 会导致上传失败,
     * 所以当遇到标题有这些非法字符, 替换成横线-.
     *
     * @param name 传入的章节标题.
     * @return 清除非法字符之后的标题.
     */
    private String removeIllegalCharacter(String name) {
        name = StrUtil.replaceChars(name, new char[]{'/', '\\', ':', '*', '?', '"', '<', '>', '|'}, StrUtil.DASHED);
        name = StrUtil.trim(name);
        return name;
    }
}
