package com.xddcodec.fs.file.service;

import com.xddcodec.fs.file.domain.qry.FileHomeUsedBytesQry;
import com.xddcodec.fs.file.domain.vo.FileHomeUsedBytesVO;
import com.xddcodec.fs.file.domain.vo.FileHomeVO;

import java.util.List;

public interface FileHomeService {

    /**
     * 获取文件仪表盘信息
     *
     * @return 文件仪表盘信息
     */
    FileHomeVO getFileHomes(FileHomeUsedBytesQry qry);
}
