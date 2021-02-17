package vip.comic18.finder.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
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
public class ComicController {
    @Autowired
    private ComicService comicService;

    @GetMapping("/download")
    public String download(@RequestParam("homePage") String homePage) throws ExecutionException, InterruptedException {
        if(homePage == null) {
            return "homePage为空";
        }
        if(!HttpUtil.isHttps(homePage) && !HttpUtil.isHttp(homePage)) {
            return StrUtil.format("homePage参数:[{}]并非http或https链接", homePage);
        }
        ComicEntity comicInfo = comicService.getComicInfo(homePage);
        comicService.downloadComic(comicInfo);
        return "下载完成";
    }
}
