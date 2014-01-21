/*
 * Copyright 2011-2012 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package piuk;

import com.google.bitcoin.core.Base58;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Wallet;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.spongycastle.crypto.BufferedBlockCipher;
import org.spongycastle.crypto.CipherParameters;
import org.spongycastle.crypto.PBEParametersGenerator;
import org.spongycastle.crypto.engines.AESEngine;
import org.spongycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.spongycastle.crypto.modes.CBCBlockCipher;
import org.spongycastle.crypto.paddings.BlockCipherPadding;
import org.spongycastle.crypto.paddings.ISO10126d2Padding;
import org.spongycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.crypto.params.ParametersWithIV;
import org.spongycastle.util.encoders.Base64;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

public class MyWallet {
    private static final int AESBlockSize = 4;
    public static final int DefaultPBKDF2Iterations = 10;
    public Map<String, Object> root;
    public JSONObject rootContainer;

    public String temporyPassword;
    public String temporySecondPassword;
    public static final double SupportedEncryptionVersion = 2.0;

    public static final NetworkParameters params = NetworkParameters.prodNet();
    public static byte[] extra_seed;

    public MyWallet(String base64Payload, String password) throws Exception {
        if (base64Payload == null || base64Payload.length() == 0 || password == null || password.length() == 0)
            throw new Exception("Error Decrypting Wallet");

        String decrypted = decryptWallet(base64Payload, password);

        if (decrypted == null || decrypted.length() == 0)
            throw new Exception("Error Decrypting Wallet");

        JSONParser parser = new JSONParser();

        this.root = (Map<String, Object>) parser.parse(decrypted);

        if (root == null)
            throw new Exception("Error Decrypting Wallet");

        temporyPassword = password;
    }


    public ECKey generateECKey() {
        return new ECKey();
    }

    // Create a new Wallet
    protected MyWallet() throws Exception {
        this.root = new HashMap<String, Object>();
        root.put("guid", UUID.randomUUID().toString());
        root.put("sharedKey", UUID.randomUUID().toString());

        List<Map<String, Object>> keys = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> address_book = new ArrayList<Map<String, Object>>();

        root.put("keys", keys);
        root.put("address_book", address_book);

        addKey(generateECKey(), "New");
    }

    public BitcoinAddress getRandomActiveAddress() throws Exception {
        List<BitcoinAddress> addresses = new ArrayList<>();
        for (Map<String, Object> key : getKeysMap()) {
            if (key.get("priv") != null && key.get("tag") == null) {
                addresses.add(new BitcoinAddress((String) key.get("addr")));
            }
        }

        if (addresses.size() == 0)
            throw new Exception("No Actived Addresses");

        Collections.shuffle(addresses);

        return addresses.get(0);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getKeysMap() {
        return (List<Map<String, Object>>) root.get("keys");
    }

    public String[] getActiveAddresses() {
        List<String> list = new ArrayList<String>();
        for (Map<String, Object> map : getKeysMap()) {
            if (map.get("tag") == null || (Long) map.get("tag") == 0)
                list.add((String) map.get("addr"));
        }
        return list.toArray(new String[list.size()]);
    }

    public String[] getAllAddresses() {
        List<String> list = new ArrayList<String>();
        for (Map<String, Object> map : getKeysMap()) {
            list.add((String) map.get("addr"));
        }
        return list.toArray(new String[list.size()]);
    }

    public String[] getArchivedAddresses() {
        List<String> list = new ArrayList<String>();
        for (Map<String, Object> map : getKeysMap()) {
            if (map.get("tag") != null && (Long) map.get("tag") == 2)
                list.add((String) map.get("addr"));
        }
        return list.toArray(new String[list.size()]);
    }

    public String[] getArchivedAddresses(int max) {
        List<String> list = new ArrayList<>();
        for (Map<String, Object> map : getKeysMap()) {
            if (list.size() == max)
                break;

            if (map.get("tag") != null && (Long) map.get("tag") == 2)
                list.add((String) map.get("addr"));
        }
        return list.toArray(new String[list.size()]);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAddressBookMap() {
        return (List<Map<String, Object>>) root.get("address_book");
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getTxNotes() {
        Map<String, String> tx_notes = (Map<String, String>) root.get("tx_notes");

        if (tx_notes == null) {
            tx_notes = new HashMap<String, String>();

            root.put("tx_notes", tx_notes);
        }

        return tx_notes;
    }

    public boolean addTxNote(Hash hash, String note) throws Exception {
        //Disallow quotes and < >
        if (StringUtils.containsAny(note, "\"'<>")) {
            throw new Exception("Note contains invalid characters");
        }

        getTxNotes().put(hash.toString(), note);

        return true;
    }

    public int getFeePolicy() {
        Map<String, Object> options = getOptions();

        int fee_policy = 0;
        if (options.containsKey("fee_policy")) {
            fee_policy = Integer.valueOf(options.get("fee_policy").toString());
        }

        return fee_policy;
    }


    @SuppressWarnings("unchecked")
    public Map<String, Object> getOptions() {
        Map<String, Object> options = (Map<String, Object>) root.get("options");

        if (options == null) {
            options = new HashMap<String, Object>();

            root.put("options", options);
        }

        return options;
    }

    public int getDoubleEncryptionPbkdf2Iterations() {
        Map<String, Object> options = getOptions();

        int iterations = DefaultPBKDF2Iterations;
        if (options.containsKey("pbkdf2_iterations")) {
            iterations = Integer.valueOf(options.get("pbkdf2_iterations").toString());
        }

        return iterations;
    }

    public int getMainPasswordPbkdf2Iterations() {
        int iterations = DefaultPBKDF2Iterations;
        if (rootContainer != null && rootContainer.containsKey("pbkdf2_iterations")) {
            iterations = Integer.valueOf(rootContainer.get("pbkdf2_iterations").toString());
        }
        return iterations;
    }

    public double getEncryptionVersionUsed() {
        double version = 0.0;
        if (rootContainer != null && rootContainer.containsKey("version")) {
            version = Double.valueOf(rootContainer.get("version").toString());
        }
        return version;
    }

    public boolean isDoubleEncrypted() {
        Object double_encryption = root.get("double_encryption");
        if (double_encryption != null)
            return (Boolean) double_encryption;
        else
            return false;
    }

    public String getGUID() {
        return (String) root.get("guid");
    }

    public String getSharedKey() {
        return (String) root.get("sharedKey");
    }

    public String getDPasswordHash() {
        return (String) root.get("dpasswordhash");
    }

    public void setTemporyPassword(String password) {
        this.temporyPassword = password;
    }

    public String getTemporyPassword() {
        return temporyPassword;
    }

    public String getTemporySecondPassword() {
        return temporySecondPassword;
    }

    public void setTemporySecondPassword(String secondPassword) {
        this.temporySecondPassword = secondPassword;
    }

    public String toJSONString() {
        return JSONValue.toJSONString(root);
    }

    public String getPayload() throws Exception {
        if (this.temporyPassword == null)
            throw new Exception("getPayload() called with temporyPassword == null");

        return encryptWallet(toJSONString(), this.temporyPassword);
    }


    public Map<String, String> getLabelMap() {
        Map<String, String> _labelMap = new HashMap<String, String>();

        List<Map<String, Object>> addressBook = this.getAddressBookMap();

        if (addressBook != null) {
            for (Map<String, Object> addr_book : addressBook) {
                _labelMap.put((String) addr_book.get("addr"),
                        (String) addr_book.get("label"));
            }
        }

        if (this.getKeysMap() != null) {
            for (Map<String, Object> key_map : this.getKeysMap()) {
                String label = (String) key_map.get("label");

                if (label != null)
                    _labelMap.put((String) key_map.get("addr"), label);
            }
        }

        return _labelMap;
    }

    public Map<String, Object> findAddressBookEntry(String address) {
        List<Map<String, Object>> addressBook = this.getAddressBookMap();

        if (addressBook != null) {
            for (Map<String, Object> addr_book : addressBook) {
                if (addr_book.get("addr").equals(address))
                    return addr_book;
            }
        }

        return null;
    }

    public Map<String, Object> findKey(String address) {
        for (Map<String, Object> key : this.getKeysMap()) {
            String addr = (String) key.get("addr");

            if (addr.equals(address))
                return key;
        }
        return null;
    }

    public boolean isMine(String address) {
        for (Map<String, Object> key : this.getKeysMap()) {
            String addr = (String) key.get("addr");

            if (addr.equals(address))
                return true;
        }

        return false;
    }

    public void setTag(String address, long tag) {
        if (this.isMine(address)) {
            findKey(address).put("tag", tag);
        }
    }

    public void addLabel(String address, String label) {
        if (this.isMine(address)) {
            findKey(address).put("label", label);
        } else {
            Map<String, Object> entry = findAddressBookEntry(address);
            if (entry != null) {
                entry.put("label", label);
            } else {
                List<Map<String, Object>> addressBook = this
                        .getAddressBookMap();

                if (addressBook == null) {
                    addressBook = new ArrayList<Map<String, Object>>();
                    root.put("address_book", addressBook);
                }

                HashMap<String, Object> map = new HashMap<String, Object>();
                map.put("addr", address);
                map.put("label", label);

                addressBook.add(map);
            }
        }
    }

    public ECKey getECKey(String address) throws Exception {
        Map<String, Object> key = this.findKey(address);

        if (key == null) {
            throw new Exception("Key not found");
        }

        String base58Priv = (String) key.get("priv");

        if (base58Priv == null) {
            throw new Exception("Watch Only Bitcoin Address");
        }

        return this.decodePK(base58Priv);
    }

    protected void addKeysTobitoinJWallet(Wallet wallet, boolean enableTagFiler, int tagFilter) throws Exception {

        wallet.keychain.clear();

        for (Map<String, Object> key : this.getKeysMap()) {

            String base58Priv = (String) key.get("priv");
            String addr = (String) key.get("addr");

            if (base58Priv == null) {
                continue;
            }

            MyECKey encoded_key = new MyECKey(addr, base58Priv, this);

            if (key.get("label") != null)
                encoded_key.setLabel((String) key.get("label"));

            Long tag = 0L;
            if (key.get("tag") != null) {
                tag = (Long) key.get("tag");

                encoded_key.setTag((int) (long) tag);
            }

            try {
                if (!enableTagFiler || tag == tagFilter)
                    wallet.addKey(encoded_key);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
    }

    public static class WalletOverride extends Wallet {
        public WalletOverride(NetworkParameters params) {
            super(params);
        }
    }

    public Wallet getBitcoinJWallet() throws Exception {
        // Construct a BitcoinJ wallet containing all our private keys
        Wallet keywallet = new WalletOverride(params);

        addKeysTobitoinJWallet(keywallet, false, 0);

        return keywallet;
    }


    public synchronized boolean removeAddressAndKey(String address) {
        final List<Map<String, Object>> keyMap = getKeysMap();

        for (int ii = 0; ii < keyMap.size(); ++ii) {
            Map<String, Object> map = keyMap.get(ii);

            if (map.get("addr").equals(address)) {
                keyMap.remove(ii);
                break;
            }
        }

        return true;
    }

    public boolean addWatchOnly(String address, String source) {
        Map<String, Object> map = new HashMap<String, Object>();

        map.put("addr", address);
        map.put("created_device_name", source);
        map.put("created_device_version", "0");

        getKeysMap().add(map);

        return true;
    }


    public String addKey(ECKey key, String label) throws Exception {
        return addKey(key, label, System.getProperty("device_name"), System.getProperty("device_version"));
    }

    public String addKey(ECKey key, String label, String device_name, String device_version) throws Exception {
        return addKey(key, label, false, device_name, device_version);
    }


    public String addKey(ECKey key, String label, boolean compressed, String device_name, String device_version) throws Exception {
        Map<String, Object> map = new HashMap<>();

        String base58Priv = new String(Base58.encode(key.getPrivKeyBytes()));

        String address;
        if (compressed)
            address = key.toAddress(params).toString();
        else
            address = key.toAddressCompressed(params).toString();

        map.put("addr", address);

        if (label != null) {
            if (label.length() == 0 || label.length() > 255)
                throw new Exception("Label must be between 0 & 255 characters");

            map.put("label", label);
        }

        if (this.isDoubleEncrypted()) {
            if (temporySecondPassword == null)
                throw new Exception("You must provide a second password");

            map.put("priv", encryptPK(base58Priv, getSharedKey(), temporySecondPassword, this.getDoubleEncryptionPbkdf2Iterations()));
        } else {
            map.put("priv", base58Priv);
        }

        map.put("created_time", System.currentTimeMillis());

        if (device_name != null)
            map.put("created_device_name", device_name);

        if (device_version != null)
            map.put("created_device_version", device_version);

        if (getKeysMap().add(map)) {
            return address;
        } else {
            throw new Exception("Error inserting address into keymap");
        }
    }

    public boolean validateSecondPassword(String secondPassword) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            {
                // N Rounds of SHA256
                byte[] data = md.digest((getSharedKey() + secondPassword).getBytes("UTF-8"));

                for (int ii = 1; ii < this.getDoubleEncryptionPbkdf2Iterations(); ++ii) {
                    data = md.digest(data);
                }

                String dpasswordhash = new String(Hex.encode(data));
                if (dpasswordhash.equals(getDPasswordHash()))
                    return true;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    private String decryptWallet(String ciphertext, String password) throws Exception {
        JSONParser parser = new JSONParser();
        try {
            JSONObject obj = (JSONObject)parser.parse(ciphertext);

            String payload = (String) obj.get("payload");
            int pbkdf2_iterations = Integer.valueOf(obj.get("pbkdf2_iterations").toString());
            double version = Integer.valueOf(obj.get("version").toString());

            if (version != SupportedEncryptionVersion)
                throw new Exception("Wallet version " + version + " not supported");

            String result = decrypt(payload, password, pbkdf2_iterations);

            rootContainer = obj;

            return result;
        } catch (ParseException e) {
            return decrypt(ciphertext, password, DefaultPBKDF2Iterations);
        }
    }

    private String encryptWallet(String text, String password) throws Exception {
        double encryptionVersionUsed = getEncryptionVersionUsed();

        if (encryptionVersionUsed == 2.0) {
            rootContainer.put("payload", encrypt(text, password, this.getMainPasswordPbkdf2Iterations()));

            return rootContainer.toJSONString();
        } else if (encryptionVersionUsed == 0.0)  {
            return encrypt(text, password, this.getMainPasswordPbkdf2Iterations());
        } else {
            throw new Exception("Unknown Encryption Version " + encryptionVersionUsed);
        }
    }


    private static byte[] copyOfRange(byte[] source, int from, int to) {
        byte[] range = new byte[to - from];
        System.arraycopy(source, from, range, 0, range.length);

        return range;
    }

    // AES 256 PBKDF2 CBC iso10126 decryption
    // 16 byte IV must be prepended to ciphertext - Compatible with crypto-js
    public static String decrypt(String ciphertext, String password, final int PBKDF2Iterations) throws Exception {
        byte[] cipherdata = Base64.decode(ciphertext);

        //Sperate the IV and cipher data
        byte[] iv = copyOfRange(cipherdata, 0, AESBlockSize * 4);
        byte[] input = copyOfRange(cipherdata, AESBlockSize * 4, cipherdata.length);

        PBEParametersGenerator generator = new PKCS5S2ParametersGenerator();
        generator.init(PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password.toCharArray()), iv, PBKDF2Iterations);
        KeyParameter keyParam = (KeyParameter)generator.generateDerivedParameters(256);

        CipherParameters params = new ParametersWithIV(keyParam, iv);

        // setup AES cipher in CBC mode with PKCS7 padding
        BlockCipherPadding padding = new ISO10126d2Padding();
        BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()), padding);
        cipher.reset();
        cipher.init(false, params);

        // create a temporary buffer to decode into (it'll include padding)
        byte[] buf = new byte[cipher.getOutputSize(input.length)];
        int len = cipher.processBytes(input, 0, input.length, buf, 0);
        len += cipher.doFinal(buf, len);

        // remove padding
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);

        // return string representation of decoded bytes
        return new String(out, "UTF-8");
    }


    private static byte[] cipherData(BufferedBlockCipher cipher, byte[] data)
            throws Exception
    {
        int minSize = cipher.getOutputSize(data.length);
        byte[] outBuf = new byte[minSize];
        int length1 = cipher.processBytes(data, 0, data.length, outBuf, 0);
        int length2 = cipher.doFinal(outBuf, length1);
        int actualLength = length1 + length2;
        byte[] result = new byte[actualLength];
        System.arraycopy(outBuf, 0, result, 0, result.length);
        return result;
    }

    // Encrypt compatible with crypto-js
    public static String encrypt(String text, String password, final int PBKDF2Iterations) throws Exception {

        if (password == null)
            throw new Exception("You must provide an ecryption password");

        // Use secure random to generate a 16 byte iv
        SecureRandom random = new SecureRandom();
        byte iv[] = new byte[AESBlockSize * 4];
        random.nextBytes(iv);

        byte[] textbytes = text.getBytes("UTF-8");

        PBEParametersGenerator generator = new PKCS5S2ParametersGenerator();
        generator.init(PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password.toCharArray()), iv, PBKDF2Iterations);
        KeyParameter keyParam = (KeyParameter)generator.generateDerivedParameters(256);

        CipherParameters params = new ParametersWithIV(keyParam, iv);

        // setup AES cipher in CBC mode with PKCS7 padding
        BlockCipherPadding padding = new ISO10126d2Padding();
        BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()), padding);
        cipher.reset();
        cipher.init(true, params);

        byte[] outBuf = cipherData(cipher, textbytes);

        // Append to IV to the output
        byte[] ivAppended = ArrayUtils.addAll(iv, outBuf);

        return new String(Base64.encode(ivAppended), "UTF-8");
    }

    // Decrypt a double encrypted private key
    public static String decryptPK(String key, String sharedKey, String password, final int PBKDF2Iterations)
            throws Exception {
        return decrypt(key, sharedKey + password, PBKDF2Iterations);
    }

    // Decrypt a double encrypted private key
    public static String encryptPK(String key, String sharedKey, String password, final int PBKDF2Iterations)
            throws Exception {
        return encrypt(key, sharedKey + password, PBKDF2Iterations);
    }

    public static ECKey decodeBase58PK(String base58Priv) throws Exception {
        byte[] privBytes = Base58.decode(base58Priv);

        // Prppend a zero byte to make the biginteger unsigned
        byte[] appendZeroByte = ArrayUtils.addAll(new byte[1], privBytes);

        ECKey ecKey = new ECKey(new BigInteger(appendZeroByte));

        return ecKey;
    }

    public static ECKey decodeBase64PK(String base64Priv) throws Exception {
        byte[] privBytes = Base64.decode(base64Priv);

        // Prppend a zero byte to make the biginteger unsigned
        byte[] appendZeroByte = ArrayUtils.addAll(new byte[1], privBytes);

        ECKey ecKey = new ECKey(new BigInteger(appendZeroByte));

        return ecKey;
    }


    public static ECKey decodeHexPK(String hex) throws Exception {
        byte[] privBytes = Hex.decode(hex);

        // Prppend a zero byte to make the biginteger unsigned
        byte[] appendZeroByte = ArrayUtils.addAll(new byte[1], privBytes);

        ECKey ecKey = new ECKey(new BigInteger(appendZeroByte));

        return ecKey;
    }

    public String decryptPK(String base58Priv) throws Exception {
        if (this.isDoubleEncrypted()) {

            if (this.temporySecondPassword == null || !this.validateSecondPassword(temporySecondPassword))
                throw new Exception("You must provide a second password");

            base58Priv = decryptPK(base58Priv, getSharedKey(), this.temporySecondPassword, this.getDoubleEncryptionPbkdf2Iterations());
        }

        return base58Priv;
    }

    public ECKey decodePK(String base58Priv) throws Exception {
        return decodeBase58PK(decryptPK(base58Priv));
    }
}
