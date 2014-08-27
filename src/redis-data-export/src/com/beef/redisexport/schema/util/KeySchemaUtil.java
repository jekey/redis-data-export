package com.beef.redisexport.schema.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.log4j.Logger;
import org.apache.oro.text.regex.MalformedPatternException;
import org.apache.oro.text.regex.MatchResult;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.PatternCompiler;
import org.apache.oro.text.regex.PatternMatcher;
import org.apache.oro.text.regex.PatternMatcherInput;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.w3c.tools.codec.Base64FormatException;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.exceptions.JedisConnectionException;
import MetoXML.XmlReader;
import MetoXML.Base.XmlNode;

import com.beef.redisexport.schema.data.KeyDesc;
import com.beef.redisexport.schema.data.KeySchema;
import com.beef.redisexport.schema.data.ValueDesc;
import com.beef.redisexport.util.DBPool;
import com.beef.util.redis.RedisDataUtil;
import com.beef.util.redis.RedisDataUtil.CompressAlgorithm;
import com.beef.util.redis.compress.CompressException;

public class KeySchemaUtil {
	private final static Logger logger = Logger.getLogger(KeySchemaUtil.class);
	
	public final static Charset DefaultCharset = Charset.forName("utf-8");
	
	public final static int DEFAULT_PRIMARY_KEY_MAX_LEN = 255;
	public final static String DEFAULT_FIELD_NAME_VALUE = "val";

	private final static char[] REGEX_META_CHARS = {
		'\\', '-', '!', '~', '@', '#', '$', '^',
		'*', '(', ')', '{', '}', '+', '=', '|', '[', ']',
		',', '.', '/', '<', '>', '?',
		};
	
	private static PatternCompiler _compiler = new Perl5Compiler();

	public static Pattern compileRegexPattern(KeyPattern keyPattern) throws MalformedPatternException {
		StringBuilder keyPatternRegexExpr = new StringBuilder();
		
		//replace meta char
		char c;
		int k;
		boolean isHandled = false;
		for(int i = 0; i < keyPattern.getKeyMatchPattern().length(); i++) {
			c = keyPattern.getKeyMatchPattern().charAt(i);
			
			if(c == '*') {
				keyPatternRegexExpr.append("(.+)");
			} else {
				isHandled = false;
				for(k = 0; k < REGEX_META_CHARS.length; k++) {
					if (REGEX_META_CHARS[k] == c) {
						isHandled = true;
						
						keyPatternRegexExpr.append("\\").append(c);
					}
				}
				if(!isHandled) {
					keyPatternRegexExpr.append(c);
				}
			}
		}
		
		return _compiler.compile(keyPatternRegexExpr.toString());
	}
	/**
	 * 
	 * @param keyPattern
	 * @param key
	 * @return value list of _variateKeyNames
	 */
	public static List<String> analyzeKeyPattern(Pattern keyRegexPattern, String key) {
		List<String> variateValList = null;
		
		PatternMatcher matcher = new Perl5Matcher();
		MatchResult result;
		PatternMatcherInput matcherInput = new PatternMatcherInput(key);
		
		if(matcher.contains(matcherInput, keyRegexPattern)) {
			variateValList = new ArrayList<String>();
			
			result = matcher.getMatch();
			for(int k = 0; k < result.groups(); k++) {
				if(k != 0) {
					variateValList.add(result.group(k));
				}
			}
		}
		
		return variateValList;
	}
	
	/*
	public static KeySchema generateDefaultSchema(
			JedisPool jedisPool, DBPool dbPool,
			String[] keyPatternArray, int scanCount) {
		KeySchema keySchema = new KeySchema();
		
		Object keyDesc;
		for(int i = 0; i < keyPatternArray.length; i++) {
			try {
				keyDesc = generateDefaultKeyDescOrKeyFieldDesc(
						jedisPool, dbPool, 
						keyPatternArray[i], scanCount);
				if(keyDesc == null) {
					continue;
				}
				
				if(KeyFieldDesc.class.isAssignableFrom(keyDesc.getClass())) {
					keySchema.getKeyFieldDescs().add((KeyFieldDesc)keyDesc);
				} else {
					keySchema.getKeyDescs().add((KeyDesc)keyDesc);
				}
			} catch(Throwable e) {
				e.printStackTrace();
				logger.error(null, e);
			}
		}
		
		return keySchema;
	}
	*/
	
	public static ValueDesc findoutValueDesc(
			//JedisPool jedisPool, DBPool dbPool,
			String keyPattern, String key, String fieldName, String value) throws IOException, Base64FormatException, CompressException {
		if(value == null || value.length() == 0) {
			return null;
		}
		
		ValueDesc valDesc = new ValueDesc();
		
		CompressAlgorithm compressAlg = RedisDataUtil.detectValueCompressAlgorithm(value);
		boolean isCompressed = false;
		if(compressAlg == CompressAlgorithm.LZF) {
			valDesc.setCompressMode(ValueDesc.COMPRESS_MODE_LZF);
			RedisDataUtil.setCompressAlgorithm(CompressAlgorithm.LZF);
			isCompressed = true;
		} else if(compressAlg == CompressAlgorithm.GZIP) {
			valDesc.setCompressMode(ValueDesc.COMPRESS_MODE_GZIP);
			RedisDataUtil.setCompressAlgorithm(CompressAlgorithm.GZIP);
			isCompressed = true;
		} else {
			valDesc.setCompressMode("");
		}
		String decodedValue;
		if(isCompressed) {
			decodedValue = new String(
					RedisDataUtil.decodeStringBytes(
							value.getBytes(DefaultCharset), isCompressed),
					DefaultCharset);
		} else {
			decodedValue = value;
		}
		
		if(decodedValue.length() > 0) {
			try {
				XmlReader xmlReader = new XmlReader();
				XmlNode xmlNode = xmlReader.StringToXmlNode(decodedValue, DefaultCharset);
				logger.info("generateDefaultKeyDesc() Detected xml node:" + xmlNode.getName());
				valDesc.setDataXml(true);
			} catch(Throwable e) {
				logger.info("generateDefaultKeyDesc() Not xml:" + decodedValue);
			}
		}

		return valDesc;
	}
	
	/**
	 * 
	 * @param jedisPool
	 * @param keyPattern
	 * @param scanCount
	 * @return key
	 */
	public static String scanKeyPatternFor1Key(
			JedisPool jedisPool, 
			String keyPattern, int scanCount) {
		Jedis jedis = null;
		try {
			ScanParams scanParams = new ScanParams();
			scanParams = new ScanParams();
			if(keyPattern != null && keyPattern.length() > 0) {
				KeyPattern pattern = KeySchemaUtil.parseKeyPattern(keyPattern);
				scanParams.match(pattern.getKeyMatchPattern());
			}
			scanParams.count(scanCount);
			ScanResult<String> result = null;
			String scanCursor = "0";
			
			jedis = jedisPool.getResource();
			while(true) {
				result = jedis.scan(scanCursor, scanParams);
				if(result == null) {
					break;
				}
				
				if(result.getResult().size() > 0) {
					break;
				}
				
				scanCursor = result.getStringCursor();
				if(scanCursor.equals("0")) {
					break;
				}
			}
			
			if(result != null && result.getResult().size() > 0) {
				return result.getResult().get(0);
			} else {
				return null;
			}
		} catch(JedisConnectionException e) {
			jedisPool.returnBrokenResource(jedis);
			throw e;
		} finally {
			jedisPool.returnResource(jedis);
		}
	}
	
	public static String getKeyTypeInRedis(JedisPool jedisPool, String key) {
		Jedis jedis = null;
		String keyType = null;
		try {
			jedis = jedisPool.getResource();
			
			keyType = jedis.type(key);
			
			if(keyType == null || keyType.equals("none")) {
				return null;
			} else {
				return keyType;
			}
		} catch(JedisConnectionException e) {
			jedisPool.returnBrokenResource(jedis);
			throw e;
		} finally {
			jedisPool.returnResource(jedis);
		}
	}
	
	/**
	 * 
	 * @param keyPattern could be test.${userId}.a
	 * @return
	 */
	public static KeyPattern parseKeyPattern(String keyPattern) {
		KeyPattern pattern = new KeyPattern();
		
		pattern.setKeyPattern(keyPattern);

		StringBuilder sb = new StringBuilder();
		int beginIndex = 0;
		int index = 0;
		int index2 = 0;
		while(beginIndex <= keyPattern.length()) {
			index = keyPattern.indexOf("${", beginIndex);
			
			if(index < 0) {
				sb.append(keyPattern.substring(beginIndex));
				break;
			}
			
			index2 = keyPattern.indexOf('}', index);
			if(index2 < 0) {
				throw new RuntimeException("Wrong keyPattern:" + keyPattern);
			}
			
			//substring before ${
			sb.append(keyPattern.substring(beginIndex, index));

			//append *
			sb.append('*');
			
			//substring between ${ and }, (variate key name)
			if(pattern.getVariateKeyNames() == null) {
				pattern.setVariateKeyNames(new ArrayList<String>());
			}
			pattern.getVariateKeyNames().add(keyPattern.substring(index + 2, index2).trim());
			
			//set beginIndex after }
			beginIndex = index2 + 1;
		}

		pattern.setKeyMatchPattern(sb.toString());
		
		return pattern;
	}

	/**
	 * test.*.a -> test_a
	 * @param keyPattern
	 * @param key
	 * @param keyDesc
	 * @return Not include nonprimarykey columns.
	 */
	public static DBTable parseDBTable(
			KeyPattern keyPattern, String key, String fieldName, String value) {
		DBTable dbTable = parseDBTableOnlyPK(keyPattern, key, fieldName);
		
		dbTable.getCols().add(new DBCol(DEFAULT_FIELD_NAME_VALUE, defaultDBColMaxLength(value.length())));
		
		return dbTable;
	}
	
	private static int defaultDBColMaxLength(int lengthOfSampleColValue) {
		if(lengthOfSampleColValue == 0) {
			return 32;
		} else if(lengthOfSampleColValue < 128) {
			return lengthOfSampleColValue * 2;
		} else {
			return (int) Math.ceil(lengthOfSampleColValue / 256) * 256; 
		}
	}

	public static DBTable parseDBTable(
			KeyPattern keyPattern, String key, String fieldName, XmlNode dataXmlNode) {
		DBTable dbTable = parseDBTableOnlyPK(keyPattern, key, fieldName);
		
		XmlNode node = (XmlNode) dataXmlNode.GetFirstChild();
		DBCol dbCol;
		while(node != null) {
			dbCol = new DBCol(node.getName().trim(), defaultDBColMaxLength(node.getContent().length()));
			dbTable.getCols().add(dbCol);
			
			node = node.getNextNode();
		}
		
		return dbTable;
	}
	
	private static DBTable parseDBTableOnlyPK(KeyPattern keyPattern, String key, String fieldName) {
		DBTable table = new DBTable();

		String tableName = parseTableName(keyPattern, fieldName);
		table.setTableName(tableName);
		table.setComment("from redis:" + keyPattern.getKeyMatchPattern());
		
		String pk;
		DBCol dbCol;
		for(int i = 0; i < keyPattern.getVariateKeyNames().size(); i++) {
			pk = keyPattern.getVariateKeyNames().get(i);
			dbCol = new DBCol(pk, DEFAULT_PRIMARY_KEY_MAX_LEN);
			table.getPrimaryKeys().add(dbCol);
			table.getPrimarykeyMap().put(pk, dbCol);
		}
		
		/*
		if(valDesc != null) {
			String[] pks = parsePrimaryKeys(valDesc);
			
			if(pks != null) {
				for(int i = 0; i < pks.length; i++) {
					table.getPrimaryKeys().add(pks[i]);
					table.getPrimarykeySet().add(pks[i]);
				}
			}
		}
		*/
		
		return table;
	}
	
	/*
	private static String[] parsePrimaryKeys(ValueDesc valDesc) {
		if(valDesc.getPrimaryKeysInData() != null && valDesc.getPrimaryKeysInData().length() > 0) {
			return splitStringByComma(valDesc.getPrimaryKeysInData());
		} else {
			return null;
		}
	}
	*/
	
	public static String[] splitStringByComma(String strTokens) {
		StringTokenizer stk = new StringTokenizer(strTokens, ",");
		
		String[] strTokenArray = new String[stk.countTokens()];
		int i = 0;
		while(stk.hasMoreTokens()) {
			strTokenArray[i++] = stk.nextToken();
		}
		
		return strTokenArray;
	}
	
	/**
	 * 
	 * @param keyPattern
	 * @param valDesc
	 * @return lowercase
	 */
	public static String parseTableName(KeyPattern keyPattern, String fieldName) {
		StringBuilder sb = new StringBuilder();
		
		char curC;
		for(int i = 0; i < keyPattern.getKeyMatchPattern().length(); i++) {
			curC = keyPattern.getKeyMatchPattern().charAt(i);
			
			if(curC == '*') {
				continue;
			}
			
			if(curC == '.' || curC == '-') {
				if(sb.charAt(sb.length() - 1) != '_') {
					sb.append('_');
				}
			} else {
				sb.append(curC);
			}
		}

		if(fieldName != null && fieldName.length() > 0) {
			if(sb.charAt(sb.length() - 1) != '_') {
				sb.append('_');
			}
			sb.append(fieldName);
		}
		
		return sb.toString().toLowerCase();
	}
	
}