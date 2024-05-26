package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Result sendCode(String phone, HttpSession session){
         //1 校验手机号
          if(RegexUtils.isPhoneInvalid(phone)){
              //如果不符合 返回错误信息
              return Result.fail(("手机号格式错误"));
          }

         //符合 生成验证码
         String code = RandomUtil.randomNumbers(6);
         //保存验证码到session
          //session.setAttribute("code",code);
          //保存到redis中
         stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
         //发送验证码
         log.info("发送短信验证码成功：{}",code);

         return  Result.ok();
     }

    public Result login(LoginFormDTO loginForm, HttpSession session){
         //校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //如果不符合 返回错误信息
            return Result.fail(("手机号格式错误"));
        }
         //从Redis 获取 校验验证码
         // Object cacheCode = session.getAttribute("code");
         String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);

         String code = loginForm.getCode();
         //不一致 报错
         if(cacheCode == null || !cacheCode.equals(code)){
             return Result.fail("验证码错误");
         }
         //一致 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //判断用户是否存在
        if(user == null){
            //不存在 创建新用户并且保存
            user = createUserWithPhone(phone);
        }

        //7保存用户信息到session
        //session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        // 7 保存用户数据到redis

        //7.1 随机生成token,作为登陆令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //7.3存储
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4 设置有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);


        //8 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);

        return user;
    }
}
