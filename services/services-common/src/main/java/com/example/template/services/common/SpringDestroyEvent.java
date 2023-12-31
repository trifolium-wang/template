package com.example.template.services.common;

import com.example.template.common.util.ThreadPoolUtil;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 公共资源关闭
 *
 * @title: SpringDestroyEvent
 * @author: trifolium
 * @date: 2023/3/9
 * @modified :
 */
@Component
public class SpringDestroyEvent implements DisposableBean {
    @Override
    public void destroy() {

        ThreadPoolUtil.shutDownNow();
    }

}
