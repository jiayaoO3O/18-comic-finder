package vip.comic18.finder.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.comic18.finder.entity.ComicEntity;
import vip.comic18.finder.service.ComicService;

import java.util.concurrent.ExecutionException;

/**
 * Created by jiayao on 2021/2/16.
 */
@RestController
@Slf4j
public class ComicController {
    @Autowired
    private ComicService comicService;

    @GetMapping("/download")
    public String download(@RequestParam("homePage") String homePage) throws ExecutionException, InterruptedException {
        if(homePage == null) {
            log.error("download->homePage为空");
            return "homePage为空";
        }
        if(!HttpUtil.isHttps(homePage) && !HttpUtil.isHttp(homePage)) {
            log.error("download->homePage参数:[{}]并非http或https链接", homePage);
            return StrUtil.format("homePage参数:[{}]并非http或https链接", homePage);
        }
        ComicEntity comicInfo = comicService.getComicInfo(homePage);
        log.info("开始下载[{}]:[{}]", comicInfo.getTitle(), homePage);
        comicService.downloadComic(comicInfo);
        log.info("下载[{}]完成", comicInfo.getTitle());
        return StrUtil.format("下载[{}]完成", comicInfo.getTitle());
    }
}
