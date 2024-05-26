package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

//        //1、获取session
//        HttpSession session = request.getSession();
//        //获取请求头中的token
//        String token = request.getHeader("authorization");
//        if(StrUtil.isBlank(token)){
//            response.setStatus(401);
//            return false;
//        }
//        //2、基于token获取redis中的用户
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
//                .entries(RedisConstants.LOGIN_USER_KEY + token);
//        //2、获取session 中的用户
//       // Object user = session.getAttribute("user");
//        //3、判断用户是否存在
//        if(userMap.isEmpty()){
//            //4 不存在 拦截 返回401状态码
//            response.setStatus(401);
//            return false;
//        }
////        if(user == null){
////            //4 不存在 拦截 返回401状态码
////            response.setStatus(401);
////            return false;
////        }
//        //5 存在 保存用户信息到ThreadLocal
//        //UserHolder.saveUser((UserDTO)user);
//        //5 将查询的hashmap 数据转为UserDto对象
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        //6 存在 保存用户信息到ThreadLocal
//        UserHolder.saveUser(userDTO);
//
//        //7 刷新token有效期
//        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //1判断是否需要拦截 ThreadLocal 是否有用户
        if(UserHolder.getUser()==null){
            response.setStatus(401);
            return false;
        }
        return true;
    }

//    @Override
//    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        //移除用户
//        UserHolder.removeUser();
//    }
}
