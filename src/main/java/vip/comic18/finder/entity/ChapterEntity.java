package vip.comic18.finder.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Created by jiayao on 2021/2/16.
 */
@Data
public class ChapterEntity implements Serializable {
    private String name;
    private List<PhotoEntity> photos;
}
