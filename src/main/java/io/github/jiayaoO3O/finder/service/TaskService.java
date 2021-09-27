package io.github.jiayaoO3O.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.MD5;
import io.github.jiayaoO3O.finder.entity.ChapterEntity;
import io.github.jiayaoO3O.finder.entity.PhotoEntity;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class TaskService {
    @ConfigProperty(name = "comic.request.cookie")
    Optional<String> cookie;

    @ConfigProperty(name = "quarkus.log.file.path")
    String logPath;

    @Inject
    Logger log;

    @Inject
    Vertx vertx;

    @Inject
    @Named("webClient")
    WebClient webClient;

    LongAdder pendingPhotoCount = new LongAdder();

    LongAdder processedPhotoCount = new LongAdder();

    private static final MD5 MD5 = cn.hutool.crypto.digest.MD5.create();

    private static final int[] rule = new int[]{2, 4, 6, 8, 10, 12, 14, 16, 18, 20};

    /**
     * @param body     漫画首页的html内容.
     * @param homePage 漫画首页url.
     * @return 返回一本漫画的所有章节内容.
     */
    public Multi<ChapterEntity> getChapterInfo(String body, String homePage) {
        var chapterEntities = new ArrayList<ChapterEntity>();
        var host = "https://" + StrUtil.subBetween(homePage, "//", "/");
        if(StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>") == null) {
            //说明该漫画是单章漫画,没有区分章节,例如王者荣耀图鉴类型的https://18comic.vip/album/203961
            var url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", ">開始閱讀<"), "href=\"", "/\"");
            if(url == null) {
                url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", ">開始閱讀<"), "href=\"", "\"");
            }
            var name = StrUtil.removeAny(StrUtil.splitTrim(this.removeIllegalCharacter(StrUtil.subBetween(body, "<h1>", "</h1>")), " ")
                    .toString(), "[", "]", ",");
            var updatedAt = DateUtil.parse(StrUtil.subBetween(StrUtil.subBetween(body, "itemprop=\"datePublished\"", "上架日期"), "content=\"", "\""));
            String id = StrUtil.subAfter(url, '/', true);
            var chapterEntity = new ChapterEntity(Integer.parseInt(id), name, host + url, updatedAt);
            chapterEntities.add(chapterEntity);
            log.info(chapterEntity.toString());
            return Multi.createFrom()
                    .iterable(chapterEntities);
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
            var updatedAt = DateUtil.parse(nameAndDate[ nameAndDate.length - 1 ]);
            String id = StrUtil.subAfter(url, '/', true);
            var chapterEntity = new ChapterEntity(Integer.parseInt(id), name, host + url, updatedAt);
            chapterEntities.add(chapterEntity);
        }
        return Multi.createFrom()
                .iterable(chapterEntities);
    }

    /**
     * @param chapterEntity 漫画的某一个章节
     * @return 该章节对应的所有漫画图片信息
     */
    public Multi<PhotoEntity> getPhotoInfo(ChapterEntity chapterEntity) {
        return this.post(chapterEntity.url())
                .onItem()
                .transformToMulti(response -> {
                    var photoEntities = new ArrayList<PhotoEntity>();
                    var body = response.bodyAsString();
                    body = StrUtil.subBetween(body, "<div class=\"row thumb-overlay-albums\" style=\"\">", "<div class=\"tab-content");
                    var photos = StrUtil.subBetweenAll(body, "data-original=\"", "\" class=");
                    for(var photo : photos) {
                        if(StrUtil.contains(photo, ".jpg")) {
                            photo = StrUtil.removeAll(photo, " id=\"");
                            var urlAndName = StrUtil.splitToArray(photo, "\"");
                            var photoEntity = new PhotoEntity(StrUtil.trim(urlAndName[ 1 ]), StrUtil.trim(urlAndName[ 0 ]));
                            photoEntities.add(photoEntity);
                            log.info(StrUtil.format("{}:chapter:[{}]-photo:[{}]-url:[{}]", this.clickPhotoCounter(true), chapterEntity.name(), photoEntity.name(), photoEntity.url()));
                        }
                    }
                    return Multi.createFrom()
                            .iterable(photoEntities);
                });
    }

    /**
     * @param photoPath     图片保存的本地路径
     * @param tempFileTuple 临时文件信息
     */
    public void process(int chapterId, String photoPath, Uni<Tuple2<String, Buffer>> tempFileTuple) {
        tempFileTuple.subscribe()
                .with(tuple2 -> this.write(tuple2.getItem1(), tuple2.getItem2())
                        .subscribe()
                        .with(succeed -> writePhoto(chapterId, photoPath, tuple2)));
    }

    private void writePhoto(int chapterId, String photoPath, Tuple2<String, Buffer> tuple2) {
        log.info(StrUtil.format("反爬处理->写入buffer到临时文件:[{}]成功", tuple2.getItem1()));
        BufferedImage bufferedImage = null;
        try(var inputStream = Files.newInputStream(Path.of(tuple2.getItem1()))) {
            bufferedImage = ImageIO.read(inputStream);
            if(bufferedImage == null) {
                log.error(StrUtil.format("反爬处理->捕获到bufferedImage为空:[{}],图片路径:[{}]", tuple2.getItem1(), photoPath));
            } else {
                this.write(photoPath, this.reverse(bufferedImage, chapterId, photoPath));
            }
        } catch(IOException e) {
            log.error(StrUtil.format("反爬处理->创建newInputStream失败:[{}]", e.getLocalizedMessage()), e);
        }
        this.delete(tuple2.getItem1());
    }

    /**
     * 获取漫画和临时文件路径的结合体
     * 因为函数的返回值只能返回一个对象,想要同时返回一个路径和buffer对象只能组合返回
     *
     * @param bufferUni 漫画图片buffer对象
     * @return 临时文件的路径和漫画buffer对象的结合体
     */
    public Uni<Tuple2<String, Buffer>> getTempFile(Uni<Buffer> bufferUni) {
        var tempFile = this.createTempFile();
        return Uni.combine()
                .all()
                .unis(tempFile, bufferUni)
                .asTuple();
    }

    public BufferedImage reverse(@NotNull BufferedImage bufferedImage, int chapterId, String photoPath) {
        //禁漫天堂最新的切割算法, 不再固定切割成10份, 而是需要通过chapterId和photoId共同确定分割块数.
        String photoId = StrUtil.subBetween(photoPath, "photo_", ".jpg");
        int piece = 10;
        String md5;
        if(chapterId >= 268850) {
            md5 = MD5.digestHex(chapterId + photoId);
            char c = md5.charAt(md5.length() - 1);
            piece = rule[ c % 10 ];
        }
        return this.reverseImage(bufferedImage, piece);
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
    public BufferedImage reverseImage(BufferedImage bufferedImage, int piece) {
        int height = bufferedImage.getHeight();
        int width = bufferedImage.getWidth();
        int preImgHeight = height / piece;
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        for(int i = 0; i < piece; i++) {
            BufferedImage subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, preImgHeight);
            graphics.drawImage(subImage, null, 0, height - (i + 1) * preImgHeight);
        }
        return result;
    }

    /**
     * 对不需要反爬虫处理的图片直接进行下载和保存.
     *
     * @param url       下载图片链接
     * @param photoPath 本地保存路径
     */
    public void getAndSaveImage(String url, String photoPath) {
        this.post(url)
                .subscribe()
                .with(response -> {
                    log.info(StrUtil.format("图片处理->成功下载图片:[{}]", url));
                    this.write(photoPath, response.body())
                            .subscribe()
                            .with(succeed -> {
                                log.info(StrUtil.format("{}:保存文件成功:[{}]", this.clickPhotoCounter(false), photoPath));
                            });
                });
    }

    /**
     * @param url 发送请求的url
     * @return 请求结果
     */
    public Uni<HttpResponse<Buffer>> post(String url) {
        var request = webClient.getAbs(url)
                .port(443)
                .followRedirects(true);
        cookie.ifPresent(cook -> request.putHeader("cookie", cook));
        return request.send()
                .onItem()
                .transform(response -> {
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
                    return response;
                })
                .onFailure()
                .retry()
                .withBackOff(Duration.ofSeconds(4L))
                .atMost(Long.MAX_VALUE)
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("网络请求:[{}]失败:[{}]", url, e.getLocalizedMessage()), e));
    }

    /**
     * @param path   保存到本地的文件路径
     * @param buffer 图片对象
     * @return 写入成功的信息
     */
    public Uni<Void> write(String path, Buffer buffer) {
        return vertx.fileSystem()
                .writeFile(path, buffer)
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("保存文件:[{}]失败:[{}]", path, e.getLocalizedMessage()), e));
    }

    public void delete(String path) {
        vertx.fileSystem()
                .delete(path)
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("反爬处理->删除文件:[{}]失败:[{}]", path, e.getLocalizedMessage()), e))
                .subscribe()
                .with(succeed -> log.info(StrUtil.format("反爬处理->删除临时文件:[{}]", path)));
    }

    /**
     * @param path          写入文件路径
     * @param bufferedImage 图片buffer
     */
    public void write(String path, BufferedImage bufferedImage) {
        try(var outputStream = Files.newOutputStream(Path.of(path))) {
            ImageIO.write(bufferedImage, "jpg", outputStream);
            log.info(StrUtil.format("{}:保存文件成功:[{}]", this.clickPhotoCounter(false), path));
        } catch(IOException e) {
            log.error(StrUtil.format("{}:保存文件失败:[{}][{}]", this.clickPhotoCounter(false), path, e.getLocalizedMessage()), e);
        }
    }

    /**
     * 如果某一章节的漫画需要反爬处理, 则需要创建一个临时文件来接收需要处理的文件.
     *
     * @return 生成临时文件的路径.
     */
    public Uni<String> createTempFile() {
        return vertx.fileSystem()
                .createTempFile(String.valueOf(System.nanoTime()), ".tmp")
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("反爬处理->创建临时文件失败:[{}]", e.getLocalizedMessage()), e));
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
            return StrUtil.format("生命周期检测->待处理页数:[{}]", this.pendingPhotoCount.longValue());
        } else {
            this.processedPhotoCount.decrement();
            return StrUtil.format("生命周期检测->已处理页数:[{}]", this.processedPhotoCount.longValue());
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
    public boolean exit() {
        var path = Path.of(logPath);
        var compareTo = 0;
        try {
            var lastModifiedTime = Files.getLastModifiedTime(path);
            compareTo = lastModifiedTime.compareTo(FileTime.from(DateUtil.toInstant(DateUtil.offsetSecond(DateUtil.date(), -32))));
        } catch(IOException e) {
            log.error(StrUtil.format("生命周期检测->读取日志错误:[{}]", e.getLocalizedMessage()), e);
        }
        return compareTo < 0 && this.processedPhotoCount.longValue() != this.pendingPhotoCount.longValue() && this.processedPhotoCount.longValue() != 0 && this.processedPhotoCount.longValue() + this.pendingPhotoCount.longValue() == 0;
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
