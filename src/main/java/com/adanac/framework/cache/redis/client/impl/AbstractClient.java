package com.adanac.framework.cache.redis.client.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.adanac.framework.cache.redis.WarningService;
import com.adanac.framework.cache.redis.exception.RedisClientException;
import com.adanac.framework.cache.redis.util.ResourceUtils;
import com.adanac.framework.cache.redis.util.SNWarningService;
import com.adanac.framework.statistics.VersionStatistics;
import com.adanac.framework.uniconfig.client.UniconfigClient;
import com.adanac.framework.uniconfig.client.UniconfigClientImpl;
import com.adanac.framework.uniconfig.client.UniconfigListener;
import com.adanac.framework.uniconfig.client.UniconfigNode;
import com.adanac.framework.utils.InPutStreamToStr;
import com.adanac.framework.utils.JacksonUtils;

import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class AbstractClient implements InitializingBean {

	private static final Logger logger = LoggerFactory.getLogger(AbstractClient.class);
	protected static final Map<String, MyJedisCluster> jedisClusterPools = new ConcurrentHashMap<String, MyJedisCluster>();

	private static final Object MUX = new Object();

	private static final String GLOBAL_WARNING_CONFIG_PATH = "/monitor.warning.service";

	protected static UniconfigNode globalWarnConfigNode;

	protected UniconfigNode redisConfigNode;
	protected String configPath = "";
	protected boolean globalConfig = false;

	public boolean isGlobalConfig() {
		return globalConfig;
	}

	public void setGlobalConfig(boolean globalConfig) {
		this.globalConfig = globalConfig;
	}

	public String getConfigPath() {
		return configPath;
	}

	public void setConfigPath(String configPath) {
		this.configPath = configPath;
	}

	public AbstractClient() {
		// this.init(this.configPath);
	}

	public AbstractClient(String configPath) {
		this.configPath = configPath;
		this.init(configPath);
	}

	public AbstractClient(String configPath, boolean globalConfig) {
		this.configPath = configPath;
		this.globalConfig = globalConfig;
		this.init(configPath, globalConfig);
	}

	private final UniconfigListener redisConfigListener = new UniconfigListener() {
		@Override
		public void execute(String oldValue, String newValue) {
			if (newValue == null || "".equals(newValue.trim())) {
				return;
			}

			if (notEqual(oldValue, newValue)) {
				synchronized (MUX) {
					Set<HostAndPort> hostAnadPort = new HashSet<HostAndPort>();
					GenericObjectPoolConfig config = new GenericObjectPoolConfig();

					hostAnadPort = XMLParser.parseHostAndPort(newValue);
					config = XMLParser.poolConfig(newValue, "");

					MyJedisCluster newPool = new MyJedisCluster(hostAnadPort, config);
					JedisCluster oldPool = jedisClusterPools.put(configPath, newPool);

					Iterator<JedisPool> poolIterator = oldPool.getClusterNodes().values().iterator();
					while (poolIterator.hasNext()) {
						JedisPool pool = poolIterator.next();
						try {
							pool.destroy();
							logger.info("JedisCluster's internal pools should be already destroyed");
						} catch (JedisConnectionException e) {
							// ok to go...
						}
					}
				}
			}
		}
	};

	public void init() {
		this.init(this.configPath, this.globalConfig);
	}

	public void init(String configPath) {
		this.init(configPath, this.globalConfig);
	}

	public void init(String configPath, boolean globalConfig) {
		this.configPath = configPath;
		Set<HostAndPort> hostAnadPort = new HashSet<HostAndPort>();
		GenericObjectPoolConfig config = new GenericObjectPoolConfig();
		if (this.configPath.endsWith(".xml")) {
			InputStream inputStream = null;
			try {
				inputStream = ResourceUtils.getResourceAsStream(configPath);
				String redisConfig = InPutStreamToStr.Inputstr2Str_byteArr(inputStream, "utf-8");
				hostAnadPort = XMLParser.parseHostAndPort(redisConfig);
				config = XMLParser.poolConfig(redisConfig, "");
				MyJedisCluster jc = new MyJedisCluster(hostAnadPort, config);
				jedisClusterPools.put(this.configPath, jc);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				throw new RedisClientException("can't read this config file path");
			} finally {
				if (null != inputStream) {
					try {
						inputStream.close();
					} catch (IOException ignore) {

					}
				}
			}

			return;
		}
		// this.configPath = globalConfig ? "GLOBAL." + configPath : "PROJECT."
		// + configPath;
		if (!jedisClusterPools.containsKey(this.configPath)) {
			synchronized (MUX) {
				if (!jedisClusterPools.containsKey(this.configPath)) {
					VersionStatistics.reportVersion(AbstractClient.class);
					UniconfigClient uniconfigClient = UniconfigClientImpl.getInstance();
					// globalWarnConfigNode =
					// uniconfigClient.getGlobalConfig(GLOBAL_WARNING_CONFIG_PATH);
					// globalWarnConfigNode.sync();
					if (globalConfig) {
						redisConfigNode = uniconfigClient.getGlobalConfig(configPath);
						redisConfigNode.sync();
					} else {
						redisConfigNode = uniconfigClient.getConfig(configPath);
						redisConfigNode.sync();
					}
					String redisConfig = redisConfigNode.getValue();
					if (redisConfig == null || "".equals(redisConfig.trim())) {
						throw new RedisClientException("can't find redis config or config content is empty.");
					}
					/*
					 * String globalWarnConfig =
					 * globalWarnConfigNode.getValue(); if (globalWarnConfig ==
					 * null || "".equals(globalWarnConfig.trim())) { throw new
					 * RedisClientException(
					 * "can't find warningService config or config is empty.");
					 * }
					 */
					hostAnadPort = XMLParser.parseHostAndPort(redisConfig);
					config = XMLParser.poolConfig(redisConfig, "");
					MyJedisCluster jc = new MyJedisCluster(hostAnadPort, config);
					jedisClusterPools.put(this.configPath, jc);
					/*
					 * globalWarnConfigNode.monitor(new UniconfigListener() {
					 * 
					 * @Override public void execute(String oldValue, String
					 * newValue) { MyJedisCluster shardedJedisPool =
					 * getRedisClusterPool(); if (shardedJedisPool != null) {
					 * logger.error("redis 配置错误!");
					 * //shardedJedisPool.setWarningService(makeWarningService()
					 * ); } } });
					 */
					redisConfigNode.monitor(redisConfigListener);
				}
			}
		}

	}

	private boolean notEqual(Set<String> setA, Set<String> setB) {
		List<String> listA = new ArrayList<String>(setA);
		List<String> listB = new ArrayList<String>(setB);
		return notEqual(listA, listB);
	}

	private boolean notEqual(List<String> listA, List<String> listB) {
		Collections.sort(listA, new Comparator<String>() {
			public int compare(String s1, String s2) {
				return s1.compareToIgnoreCase(s2);
			}
		});
		Collections.sort(listB, new Comparator<String>() {
			public int compare(String s1, String s2) {
				return s1.compareToIgnoreCase(s2);
			}
		});
		StringBuilder strA = new StringBuilder();
		for (String s : listA) {
			strA.append(s.trim());
		}
		StringBuilder strB = new StringBuilder();
		for (String s : listB) {
			strB.append(s.trim());
		}
		return notEqual(strA.toString(), strB.toString());
	}

	private boolean notEqual(String strA, String strB) {
		return !(strA == null || strB == null) && !strA.trim().equalsIgnoreCase(strB.trim());
	}

	public MyJedisCluster getRedisClusterPool() {
		return jedisClusterPools.get(configPath);
	}

	protected void destroy() {
		synchronized (MUX) {
			MyJedisCluster cluster = getRedisClusterPool();

			Iterator<JedisPool> poolIterator = cluster.getClusterNodes().values().iterator();
			while (poolIterator.hasNext()) {
				JedisPool pool = poolIterator.next();
				try {
					pool.destroy();
					logger.info("JedisCluster's internal pools should be already destroyed");
				} catch (JedisConnectionException e) {
					// ok to go...
				}
			}
			cluster.close();
			jedisClusterPools.remove(configPath);
		}
	}

	protected SNWarningService makeWarningService() {
		if (globalWarnConfigNode == null || null == globalWarnConfigNode.getValue() || "".equals(globalWarnConfigNode))
			return new SNWarningService("", "", "");
		Properties properties = new Properties();
		StringReader reader = new StringReader(globalWarnConfigNode.getValue());
		try {
			properties.load(reader);
		} catch (IOException e) {
			throw new RedisClientException("load warning config error");
		}
		String appCode = UniconfigClientImpl.getInstance().getAppCode();
		String url = (String) properties.get("monitor.service.server");
		String globalPhones = (String) properties.get("sedis.warning.phones");
		String phones = XMLParser.parse(redisConfigNode.getValue(), "phones");
		Set<String> phonesSet = new HashSet<String>();
		phonesSet.addAll(Arrays.asList(globalPhones.split("\\,")));
		phonesSet.addAll(Arrays.asList(phones.split("\\,")));
		StringBuilder allPhones = new StringBuilder();
		for (String str : phonesSet) {
			allPhones.append(",");
			allPhones.append(str);
		}
		allPhones.delete(0, 1);
		return new SNWarningService(appCode, url, allPhones.toString());
	}

	/*
	 * public String getPoolStatus() { return
	 * PoolStatusUtil.getPoolStatus(jedisClusterPools.get(this.configPath)); }
	 */

	private static class XMLParser {
		private static Logger logger = LoggerFactory.getLogger(XMLParser.class);

		private static XPath path;

		private static Document doc;

		private static String getString(Object node, String expression) throws XPathExpressionException {
			return (String) path.evaluate(expression, node, XPathConstants.STRING);
		}

		private static NodeList getList(Object node, String expression) throws XPathExpressionException {
			return (NodeList) path.evaluate(expression, node, XPathConstants.NODESET);
		}

		private static Node getNode(Object node, String expression) throws XPathExpressionException {
			return (Node) path.evaluate(expression, node, XPathConstants.NODE);
		}

		public static String parse(String redisConfig, String key) {
			try {
				StringReader reader = new StringReader(redisConfig);
				InputSource is = new InputSource(reader);
				DocumentBuilder dbd = DocumentBuilderFactory.newInstance().newDocumentBuilder();
				doc = dbd.parse(is);
				path = XPathFactory.newInstance().newXPath();
				Node rootN = getNode(doc, "config");
				if (null == rootN) {
					throw new RedisClientException("Invalid xml format, can't find <config> root node!");
				}
				return getString(rootN, key);
			} catch (Exception ex) {
				throw new RedisClientException("Fail to parse redis configure file.", ex);
			}
		}

		public static GenericObjectPoolConfig poolConfig(String redisConfig, String field) {
			try {
				StringReader reader = new StringReader(redisConfig);
				InputSource is = new InputSource(reader);
				DocumentBuilder dbd = DocumentBuilderFactory.newInstance().newDocumentBuilder();
				doc = dbd.parse(is);
				path = XPathFactory.newInstance().newXPath();
				Node rootN = getNode(doc, "config");
				if (null == rootN) {
					throw new RedisClientException("Invalid xml format, can't find <config> root node!");
				}
				String timeOut = getString(rootN, "timeOut");
				if (null == timeOut || "".equals(timeOut.trim())) {
					timeOut = "2000";
				}
				String password = getString(rootN, "password");
				if (null == password || "".equals(password.trim())) {
					password = null;
				}
				String dbIndex = getString(rootN, "dbIndex");
				if (null == dbIndex || "".equals(dbIndex.trim())) {
					dbIndex = "0";
				}
				GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
				Node poolConfigNode = getNode(rootN, "poolConfig");
				if (poolConfigNode != null) {
					poolConfig.setMaxTotal(Integer.MAX_VALUE);
					poolConfig.setMaxWaitMillis(200);
					poolConfig.setBlockWhenExhausted(false);
					String maxIdle = getString(poolConfigNode, "maxIdle");
					if (null != maxIdle && !"".equals(maxIdle.trim())) {
						poolConfig.setMaxIdle(Integer.valueOf(maxIdle));
					}
					String minIdle = getString(poolConfigNode, "minIdle");
					if (null != minIdle && !"".equals(minIdle.trim())) {
						poolConfig.setMinIdle(Integer.valueOf(minIdle));
					}
					String lifo = getString(poolConfigNode, "lifo");
					if (null != lifo && !"".equals(lifo.trim())) {
						poolConfig.setLifo(Boolean.valueOf(lifo));
					}
					String minEvictableIdleTimeMillis = getString(poolConfigNode, "minEvictableIdleTimeMillis");
					if (null != minEvictableIdleTimeMillis && !"".equals(minEvictableIdleTimeMillis.trim())) {
						poolConfig.setMinEvictableIdleTimeMillis(Long.valueOf(minEvictableIdleTimeMillis));
					} else {
						poolConfig.setMinEvictableIdleTimeMillis(60000L);
					}
					String softMinEvictableIdleTimeMillis = getString(poolConfigNode, "softMinEvictableIdleTimeMillis");
					if (null != softMinEvictableIdleTimeMillis && !"".equals(softMinEvictableIdleTimeMillis.trim())) {
						poolConfig.setSoftMinEvictableIdleTimeMillis(Long.valueOf(softMinEvictableIdleTimeMillis));
					}
					String numTestsPerEvictionRun = getString(poolConfigNode, "numTestsPerEvictionRun");
					if (null != numTestsPerEvictionRun && !"".equals(numTestsPerEvictionRun.trim())) {
						poolConfig.setNumTestsPerEvictionRun(Integer.valueOf(numTestsPerEvictionRun));
					} else {
						poolConfig.setNumTestsPerEvictionRun(-1);
					}
					String evictionPolicyClassName = getString(poolConfigNode, "evictionPolicyClassName");
					if (null != evictionPolicyClassName && !"".equals(evictionPolicyClassName.trim())) {
						poolConfig.setEvictionPolicyClassName(evictionPolicyClassName);
					}
					String testOnBorrow = getString(poolConfigNode, "testOnBorrow");
					if (null != testOnBorrow && !"".equals(testOnBorrow.trim())) {
						// 获取连接池是否检测可用性
						poolConfig.setTestOnBorrow(Boolean.valueOf(testOnBorrow));
					}
					String testOnReturn = getString(poolConfigNode, "testOnReturn");
					if (null != testOnReturn && !"".equals(testOnReturn.trim())) {
						// 归还时是否检测可用性
						poolConfig.setTestOnReturn(Boolean.valueOf(testOnReturn));
					}
					String testWhileIdle = getString(poolConfigNode, "testWhileIdle");
					if (null != testWhileIdle && !"".equals(testWhileIdle.trim())) {
						// 空闲时是否检测可用性
						poolConfig.setTestWhileIdle(Boolean.valueOf(testWhileIdle));
					} else {
						poolConfig.setTestWhileIdle(true);
					}
					String timeBetweenEvictionRunsMillis = getString(poolConfigNode, "timeBetweenEvictionRunsMillis");
					if (null != timeBetweenEvictionRunsMillis && !"".equals(timeBetweenEvictionRunsMillis.trim())) {
						poolConfig.setTimeBetweenEvictionRunsMillis(Long.valueOf(timeBetweenEvictionRunsMillis));
					} else {
						poolConfig.setTimeBetweenEvictionRunsMillis(30000L);
					}
					String jmxEnabled = getString(poolConfigNode, "jmxEnabled");
					if (null != jmxEnabled && !"".equals(jmxEnabled.trim())) {
						poolConfig.setJmxEnabled(Boolean.valueOf(jmxEnabled));
					}
					String jmxNamePrefix = getString(poolConfigNode, "jmxNamePrefix");
					if (null != jmxNamePrefix && !"".equals(jmxNamePrefix.trim())) {
						poolConfig.setJmxNamePrefix(jmxNamePrefix);
					}
				}
				return poolConfig;
			} catch (Exception ex) {
				throw new RedisClientException("Fail to parse redis configure file.", ex);
			}
		}

		public static Set<HostAndPort> parseHostAndPort(String redisConfig) {
			try {
				StringReader reader = new StringReader(redisConfig);
				InputSource is = new InputSource(reader);
				DocumentBuilder dbd = DocumentBuilderFactory.newInstance().newDocumentBuilder();
				doc = dbd.parse(is);
				path = XPathFactory.newInstance().newXPath();
				Node rootN = getNode(doc, "config");
				if (null == rootN) {
					throw new RedisClientException("Invalid xml format, can't find <config> root node!");
				}

				Node hostAndPortNodes = getNode(rootN, "hostAndPorts");
				Set<HostAndPort> hostAndPorts = new HashSet<HostAndPort>();
				if (hostAndPortNodes != null) {
					NodeList hostAndPortList = getList(hostAndPortNodes, "hostAndPort");
					for (int i = 0; i < hostAndPortList.getLength(); i++) {
						Node hostAndPortNode = hostAndPortList.item(i);
						String hostAndPort = hostAndPortNode.getTextContent();
						String[] ipAndPort = hostAndPort.split(":");
						if (null == ipAndPort[0] || "".equals(ipAndPort[0].trim())) {
							// throw new RedisClientException("Configuration
							// error,sentinel host can not be null");
						}
						if (null == ipAndPort[1] || "".equals(ipAndPort[1].trim())) {
							ipAndPort[1] = "6379";
						}
						HostAndPort hap = new HostAndPort(ipAndPort[0].trim(), Integer.parseInt(ipAndPort[1].trim()));
						hostAndPorts.add(hap);
					}

				}
				return hostAndPorts;
			} catch (Exception ex) {
				throw new RedisClientException("Fail to parse redis configure file.", ex);
			}
		}

		public static List<String> parseMasters(String redisConfig) {
			try {
				StringReader reader = new StringReader(redisConfig);
				InputSource is = new InputSource(reader);
				DocumentBuilder dbd = DocumentBuilderFactory.newInstance().newDocumentBuilder();
				doc = dbd.parse(is);
				path = XPathFactory.newInstance().newXPath();
				Node rootN = getNode(doc, "config");
				if (null == rootN) {
					throw new RedisClientException("Invalid xml format, can't find <config> root node!");
				}
				List<String> masters = new ArrayList<String>();
				Node mastersNode = getNode(rootN, "shards");
				if (mastersNode != null) {
					NodeList masterNodes = getList(mastersNode, "shardName");
					for (int i = 0; i < masterNodes.getLength(); i++) {
						String master = masterNodes.item(i).getTextContent();
						masters.add(master);
					}
				}
				return masters;
			} catch (Exception ex) {
				throw new RedisClientException("Fail to parse redis configure file.", ex);
			}
		}

		public static List<String> forceMasterKeys(String redisConfig) {
			try {
				StringReader reader = new StringReader(redisConfig);
				InputSource is = new InputSource(reader);
				DocumentBuilder dbd = DocumentBuilderFactory.newInstance().newDocumentBuilder();
				doc = dbd.parse(is);
				path = XPathFactory.newInstance().newXPath();
				Node rootN = getNode(doc, "config");
				if (null == rootN) {
					throw new RedisClientException("Invalid xml format, can't find <config> root node!");
				}
				List<String> forceMasterKeys = new ArrayList<String>();
				Node forceMasterKeysNode = getNode(rootN, "forceMasterkeys");
				if (forceMasterKeysNode != null) {
					NodeList keyPatternNodes = getList(forceMasterKeysNode, "keyPattern");
					for (int i = 0; i < keyPatternNodes.getLength(); i++) {
						String master = keyPatternNodes.item(i).getTextContent();
						forceMasterKeys.add(master);
					}
				}
				return forceMasterKeys;
			} catch (Exception ex) {
				throw new RedisClientException("Fail to parse redis configure file.", ex);
			}
		}

		public static MyJedisCluster parse(String redisConfig, WarningService warningService) {
			try {
				StringReader reader = new StringReader(redisConfig);
				InputSource is = new InputSource(reader);
				DocumentBuilder dbd = DocumentBuilderFactory.newInstance().newDocumentBuilder();
				doc = dbd.parse(is);
				path = XPathFactory.newInstance().newXPath();
				Node rootN = getNode(doc, "config");
				if (null == rootN) {
					throw new RedisClientException("Invalid xml format, can't find <config> root node!");
				}
				String timeOut = getString(rootN, "timeOut");
				if (null == timeOut || "".equals(timeOut.trim())) {
					timeOut = "2000";
				}
				String password = getString(rootN, "password");
				if (null == password || "".equals(password.trim())) {
					password = null;
				}
				String dbIndex = getString(rootN, "dbIndex");
				if (null == dbIndex || "".equals(dbIndex.trim())) {
					dbIndex = "0";
				}
				GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
				Node poolConfigNode = getNode(rootN, "poolConfig");
				if (poolConfigNode != null) {
					poolConfig.setMaxTotal(Integer.MAX_VALUE);
					poolConfig.setMaxWaitMillis(200);
					poolConfig.setBlockWhenExhausted(false);
					String maxIdle = getString(poolConfigNode, "maxIdle");
					if (null != maxIdle && !"".equals(maxIdle.trim())) {
						poolConfig.setMaxIdle(Integer.valueOf(maxIdle));
					}
					String minIdle = getString(poolConfigNode, "minIdle");
					if (null != minIdle && !"".equals(minIdle.trim())) {
						poolConfig.setMinIdle(Integer.valueOf(minIdle));
					}
					String lifo = getString(poolConfigNode, "lifo");
					if (null != lifo && !"".equals(lifo.trim())) {
						poolConfig.setLifo(Boolean.valueOf(lifo));
					}
					String minEvictableIdleTimeMillis = getString(poolConfigNode, "minEvictableIdleTimeMillis");
					if (null != minEvictableIdleTimeMillis && !"".equals(minEvictableIdleTimeMillis.trim())) {
						poolConfig.setMinEvictableIdleTimeMillis(Long.valueOf(minEvictableIdleTimeMillis));
					} else {
						poolConfig.setMinEvictableIdleTimeMillis(60000L);
					}
					String softMinEvictableIdleTimeMillis = getString(poolConfigNode, "softMinEvictableIdleTimeMillis");
					if (null != softMinEvictableIdleTimeMillis && !"".equals(softMinEvictableIdleTimeMillis.trim())) {
						poolConfig.setSoftMinEvictableIdleTimeMillis(Long.valueOf(softMinEvictableIdleTimeMillis));
					}
					String numTestsPerEvictionRun = getString(poolConfigNode, "numTestsPerEvictionRun");
					if (null != numTestsPerEvictionRun && !"".equals(numTestsPerEvictionRun.trim())) {
						poolConfig.setNumTestsPerEvictionRun(Integer.valueOf(numTestsPerEvictionRun));
					} else {
						poolConfig.setNumTestsPerEvictionRun(-1);
					}
					String evictionPolicyClassName = getString(poolConfigNode, "evictionPolicyClassName");
					if (null != evictionPolicyClassName && !"".equals(evictionPolicyClassName.trim())) {
						poolConfig.setEvictionPolicyClassName(evictionPolicyClassName);
					}
					String testOnBorrow = getString(poolConfigNode, "testOnBorrow");
					if (null != testOnBorrow && !"".equals(testOnBorrow.trim())) {
						// 获取连接池是否检测可用性
						poolConfig.setTestOnBorrow(Boolean.valueOf(testOnBorrow));
					}
					String testOnReturn = getString(poolConfigNode, "testOnReturn");
					if (null != testOnReturn && !"".equals(testOnReturn.trim())) {
						// 归还时是否检测可用性
						poolConfig.setTestOnReturn(Boolean.valueOf(testOnReturn));
					}
					String testWhileIdle = getString(poolConfigNode, "testWhileIdle");
					if (null != testWhileIdle && !"".equals(testWhileIdle.trim())) {
						// 空闲时是否检测可用性
						poolConfig.setTestWhileIdle(Boolean.valueOf(testWhileIdle));
					} else {
						poolConfig.setTestWhileIdle(true);
					}
					String timeBetweenEvictionRunsMillis = getString(poolConfigNode, "timeBetweenEvictionRunsMillis");
					if (null != timeBetweenEvictionRunsMillis && !"".equals(timeBetweenEvictionRunsMillis.trim())) {
						poolConfig.setTimeBetweenEvictionRunsMillis(Long.valueOf(timeBetweenEvictionRunsMillis));
					} else {
						poolConfig.setTimeBetweenEvictionRunsMillis(30000L);
					}
					String jmxEnabled = getString(poolConfigNode, "jmxEnabled");
					if (null != jmxEnabled && !"".equals(jmxEnabled.trim())) {
						poolConfig.setJmxEnabled(Boolean.valueOf(jmxEnabled));
					}
					String jmxNamePrefix = getString(poolConfigNode, "jmxNamePrefix");
					if (null != jmxNamePrefix && !"".equals(jmxNamePrefix.trim())) {
						poolConfig.setJmxNamePrefix(jmxNamePrefix);
					}
				}
				// redis3.0 hostAndPort
				Node hostAndPortNodes = getNode(rootN, "hostAndPorts");
				Set<HostAndPort> hostAndPorts = new HashSet<HostAndPort>();
				if (hostAndPortNodes != null) {
					NodeList hostAndPortList = getList(hostAndPortNodes, "hostAndPort");
					for (int i = 0; i < hostAndPortList.getLength(); i++) {
						Node hostAndPortNode = hostAndPortList.item(i);
						String hostAndPort = hostAndPortNode.getTextContent();
						String[] ipAndPort = hostAndPort.split(":");
						if (null == ipAndPort[0] || "".equals(ipAndPort[0].trim())) {
							// throw new RedisClientException("Configuration
							// error,sentinel host can not be null");
						}
						if (null == ipAndPort[1] || "".equals(ipAndPort[1].trim())) {
							ipAndPort[1] = "26379";
						}
						HostAndPort hap = new HostAndPort(ipAndPort[0], Integer.parseInt(ipAndPort[1]));
						hostAndPorts.add(hap);
					}

				}

				String execTimeThreshold = getString(rootN, "execTimeThreshold");
				if (null == execTimeThreshold || "".equals(execTimeThreshold.trim())) {
					execTimeThreshold = "20";
				}
				return new MyJedisCluster(hostAndPorts, poolConfig);
			} catch (IOException e) {
				logger.error("IOException!", e);
				throw new RedisClientException("IOException!", e);
			} catch (Exception ex) {
				throw new RedisClientException("Fail to parse redis configure file.", ex);
			}
		}
	}

	public void afterPropertiesSet() {
		this.init(this.configPath, this.globalConfig);

	}

	/**
	 * <p>对象序列化成字符串/p>
	 * 
	 */
	public static String objectToStr(List<Object> list) {
		try {
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
			objectOutputStream.writeObject(list);
			String serStr = byteArrayOutputStream.toString("UTF-8");
			return serStr;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}

	}

	/**
	 * <p>集合转JSON字符串/p>
	 * 
	 */
	public static String objectToJson(Object obj) {
		try {
			if (obj == null) {
				return null;
			}

			if (isBasicType(obj)) {
				return String.valueOf(obj);
			} else {
				return JacksonUtils.getInstance().obj2Json(obj);
			}

		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}

	}

	/**
	 * 是否为基本类型
	 * @param obj
	 * @return
	 */
	private static boolean isBasicType(Object obj) {
		if (obj instanceof String || obj instanceof Integer || obj instanceof Long || obj instanceof Float
				|| obj instanceof Double || obj instanceof Byte || obj instanceof Character || obj instanceof Boolean
				|| obj instanceof Short) {

			return true;
		}
		return false;
	}

}
