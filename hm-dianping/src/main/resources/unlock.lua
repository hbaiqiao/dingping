--比较线程标示与锁中的标示是否一致
if(redis.call('get',KEY[1]==ARGV[1])) then
      --释放锁
      return redis.call('del',KEYS[1])
end
return 0