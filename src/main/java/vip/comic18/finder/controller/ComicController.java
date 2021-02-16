package vip.comic18.finder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.comic18.finder.entity.ComicEntity;
import vip.comic18.finder.service.ComicService;

/**
 * Created by jiayao on 2021/2/16.
 */
@RestController
public class ComicController {
    @Autowired
    private ComicService comicService;

    @GetMapping("/download")
    public String download(@RequestParam("homePage") String homePage) {
        ComicEntity comicInfo = comicService.getComicInfo(homePage);
        comicService.downloadComic(comicInfo);
        return comicInfo.toString();
    }
}
