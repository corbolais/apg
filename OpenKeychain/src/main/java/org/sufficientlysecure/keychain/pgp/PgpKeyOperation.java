/*
 * Copyright (C) 2012-2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2014 Vincent Breitmoser <v.breitmoser@mugenguild.com>
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

package org.sufficientlysecure.keychain.pgp;

import org.spongycastle.bcpg.PublicKeyAlgorithmTags;
import org.spongycastle.bcpg.S2K;
import org.spongycastle.bcpg.sig.Features;
import org.spongycastle.bcpg.sig.KeyFlags;
import org.spongycastle.jce.spec.ElGamalParameterSpec;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyFlags;
import org.spongycastle.openpgp.PGPKeyPair;
import org.spongycastle.openpgp.PGPPrivateKey;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPSecretKey;
import org.spongycastle.openpgp.PGPSecretKeyRing;
import org.spongycastle.openpgp.PGPSignature;
import org.spongycastle.openpgp.PGPSignatureGenerator;
import org.spongycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.spongycastle.openpgp.PGPUserAttributeSubpacketVector;
import org.spongycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.spongycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.spongycastle.openpgp.operator.PGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.PGPDigestCalculator;
import org.spongycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.spongycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.spongycastle.openpgp.operator.jcajce.NfcSyncPGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.jcajce.NfcSyncPGPContentSignerBuilder.NfcInteractionNeeded;
import org.spongycastle.util.encoders.Hex;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.operations.results.EditKeyResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult.LogType;
import org.sufficientlysecure.keychain.operations.results.OperationResult.OperationLog;
import org.sufficientlysecure.keychain.operations.results.PgpEditKeyResult;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel.Algorithm;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel.ChangeUnlockParcel;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel.Curve;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel.SubkeyAdd;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel.NfcSignOperationsBuilder;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel.NfcKeyToCardOperationsBuilder;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.util.IterableIterator;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Passphrase;
import org.sufficientlysecure.keychain.util.Primes;
import org.sufficientlysecure.keychain.util.ProgressScaler;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class is the single place where ALL operations that actually modify a PGP public or secret
 * key take place.
 * <p/>
 * Note that no android specific stuff should be done here, ie no imports from com.android.
 * <p/>
 * All operations support progress reporting to a Progressable passed on initialization.
 * This indicator may be null.
 */
public class PgpKeyOperation {

    private Stack<Progressable> mProgress;
    private AtomicBoolean mCancelled;

    public PgpKeyOperation(Progressable progress) {
        super();
        if (progress != null) {
            mProgress = new Stack<>();
            mProgress.push(progress);
        }
    }

    public PgpKeyOperation(Progressable progress, AtomicBoolean cancelled) {
        this(progress);
        mCancelled = cancelled;
    }

    private boolean checkCancelled() {
        return mCancelled != null && mCancelled.get();
    }

    private void subProgressPush(int from, int to) {
        if (mProgress == null) {
            return;
        }
        mProgress.push(new ProgressScaler(mProgress.peek(), from, to, 100));
    }
    private void subProgressPop() {
        if (mProgress == null) {
            return;
        }
        if (mProgress.size() == 1) {
            throw new RuntimeException("Tried to pop progressable without prior push! "
                    + "This is a programming error, please file a bug report.");
        }
        mProgress.pop();
    }

    private void progress(int message, int current) {
        if (mProgress == null) {
            return;
        }
        mProgress.peek().setProgress(message, current, 100);
    }

    private ECGenParameterSpec getEccParameterSpec(Curve curve) {
        switch (curve) {
            case NIST_P256: return new ECGenParameterSpec("P-256");
            case NIST_P384: return new ECGenParameterSpec("P-384");
            case NIST_P521: return new ECGenParameterSpec("P-521");

            // @see SaveKeyringParcel
            // case BRAINPOOL_P256: return new ECGenParameterSpec("brainpoolp256r1");
            // case BRAINPOOL_P384: return new ECGenParameterSpec("brainpoolp384r1");
            // case BRAINPOOL_P512: return new ECGenParameterSpec("brainpoolp512r1");
        }
        throw new RuntimeException("Invalid choice! (can't happen)");
    }

    /** Creates new secret key. */
    private PGPKeyPair createKey(SubkeyAdd add, Date creationTime, OperationLog log, int indent) {

        try {
            // Some safety checks
            if (add.mAlgorithm == Algorithm.ECDH || add.mAlgorithm == Algorithm.ECDSA) {
                if (add.mCurve == null) {
                    log.add(LogType.MSG_CR_ERROR_NO_CURVE, indent);
                    return null;
                }
            } else {
                if (add.mKeySize == null) {
                    log.add(LogType.MSG_CR_ERROR_NO_KEYSIZE, indent);
                    return null;
                }
                if (add.mKeySize < 512) {
                    log.add(LogType.MSG_CR_ERROR_KEYSIZE_512, indent);
                    return null;
                }
            }

            int algorithm;
            KeyPairGenerator keyGen;

            switch (add.mAlgorithm) {
                case DSA: {
                    if ((add.mFlags & (PGPKeyFlags.CAN_ENCRYPT_COMMS | PGPKeyFlags.CAN_ENCRYPT_STORAGE)) > 0) {
                        log.add(LogType.MSG_CR_ERROR_FLAGS_DSA, indent);
                        return null;
                    }
                    progress(R.string.progress_generating_dsa, 30);
                    keyGen = KeyPairGenerator.getInstance("DSA", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    keyGen.initialize(add.mKeySize, new SecureRandom());
                    algorithm = PGPPublicKey.DSA;
                    break;
                }

                case ELGAMAL: {
                    if ((add.mFlags & (PGPKeyFlags.CAN_SIGN | PGPKeyFlags.CAN_CERTIFY)) > 0) {
                        log.add(LogType.MSG_CR_ERROR_FLAGS_ELGAMAL, indent);
                        return null;
                    }
                    progress(R.string.progress_generating_elgamal, 30);
                    keyGen = KeyPairGenerator.getInstance("ElGamal", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    BigInteger p = Primes.getBestPrime(add.mKeySize);
                    BigInteger g = new BigInteger("2");

                    ElGamalParameterSpec elParams = new ElGamalParameterSpec(p, g);

                    keyGen.initialize(elParams);
                    algorithm = PGPPublicKey.ELGAMAL_ENCRYPT;
                    break;
                }

                case RSA: {
                    progress(R.string.progress_generating_rsa, 30);
                    keyGen = KeyPairGenerator.getInstance("RSA", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    keyGen.initialize(add.mKeySize, new SecureRandom());

                    algorithm = PGPPublicKey.RSA_GENERAL;
                    break;
                }

                case ECDSA: {
                    if ((add.mFlags & (PGPKeyFlags.CAN_ENCRYPT_COMMS | PGPKeyFlags.CAN_ENCRYPT_STORAGE)) > 0) {
                        log.add(LogType.MSG_CR_ERROR_FLAGS_ECDSA, indent);
                        return null;
                    }
                    progress(R.string.progress_generating_ecdsa, 30);
                    ECGenParameterSpec ecParamSpec = getEccParameterSpec(add.mCurve);
                    keyGen = KeyPairGenerator.getInstance("ECDSA", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    keyGen.initialize(ecParamSpec, new SecureRandom());

                    algorithm = PGPPublicKey.ECDSA;
                    break;
                }

                case ECDH: {
                    // make sure there are no sign or certify flags set
                    if ((add.mFlags & (PGPKeyFlags.CAN_SIGN | PGPKeyFlags.CAN_CERTIFY)) > 0) {
                        log.add(LogType.MSG_CR_ERROR_FLAGS_ECDH, indent);
                        return null;
                    }
                    progress(R.string.progress_generating_ecdh, 30);
                    ECGenParameterSpec ecParamSpec = getEccParameterSpec(add.mCurve);
                    keyGen = KeyPairGenerator.getInstance("ECDH", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    keyGen.initialize(ecParamSpec, new SecureRandom());

                    algorithm = PGPPublicKey.ECDH;
                    break;
                }

                default: {
                    log.add(LogType.MSG_CR_ERROR_UNKNOWN_ALGO, indent);
                    return null;
                }
            }

            // build new key pair
            return new JcaPGPKeyPair(algorithm, keyGen.generateKeyPair(), creationTime);

        } catch(NoSuchProviderException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        } catch(NoSuchAlgorithmException e) {
            log.add(LogType.MSG_CR_ERROR_UNKNOWN_ALGO, indent);
            return null;
        } catch(PGPException e) {
            Log.e(Constants.TAG, "internal pgp error", e);
            log.add(LogType.MSG_CR_ERROR_INTERNAL_PGP, indent);
            return null;
        }
    }

    public PgpEditKeyResult createSecretKeyRing(SaveKeyringParcel saveParcel) {

        OperationLog log = new OperationLog();
        int indent = 0;

        try {

            log.add(LogType.MSG_CR, indent);
            progress(R.string.progress_building_key, 0);
            indent += 1;

            if (saveParcel.mAddSubKeys.isEmpty()) {
                log.add(LogType.MSG_CR_ERROR_NO_MASTER, indent);
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
            }

            if (saveParcel.mAddUserIds.isEmpty()) {
                log.add(LogType.MSG_CR_ERROR_NO_USER_ID, indent);
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
            }

            SubkeyAdd add = saveParcel.mAddSubKeys.remove(0);
            if ((add.mFlags & KeyFlags.CERTIFY_OTHER) != KeyFlags.CERTIFY_OTHER) {
                log.add(LogType.MSG_CR_ERROR_NO_CERTIFY, indent);
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
            }

            if (add.mExpiry == null) {
                log.add(LogType.MSG_CR_ERROR_NULL_EXPIRY, indent);
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
            }

            Date creationTime = new Date();

            subProgressPush(10, 30);
            PGPKeyPair keyPair = createKey(add, creationTime, log, indent);
            subProgressPop();

            // return null if this failed (an error will already have been logged by createKey)
            if (keyPair == null) {
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
            }

            progress(R.string.progress_building_master_key, 40);

            // Build key encrypter and decrypter based on passphrase
            PGPDigestCalculator encryptorHashCalc = new JcaPGPDigestCalculatorProviderBuilder()
                    .build().get(PgpConstants.SECRET_KEY_ENCRYPTOR_HASH_ALGO);
            PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                    PgpConstants.SECRET_KEY_ENCRYPTOR_SYMMETRIC_ALGO,
                    encryptorHashCalc, PgpConstants.SECRET_KEY_ENCRYPTOR_S2K_COUNT)
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build("".toCharArray());

            PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder()
                    .build().get(PgpConstants.SECRET_KEY_SIGNATURE_CHECKSUM_HASH_ALGO);
            PGPSecretKey masterSecretKey = new PGPSecretKey(keyPair.getPrivateKey(), keyPair.getPublicKey(),
                    sha1Calc, true, keyEncryptor);

            PGPSecretKeyRing sKR = new PGPSecretKeyRing(
                    masterSecretKey.getEncoded(), new JcaKeyFingerprintCalculator());

            subProgressPush(50, 100);
            CryptoInputParcel cryptoInput = new CryptoInputParcel(creationTime, new Passphrase(""));
            return internal(sKR, masterSecretKey, add.mFlags, add.mExpiry, cryptoInput, saveParcel, log, indent);

        } catch (PGPException e) {
            log.add(LogType.MSG_CR_ERROR_INTERNAL_PGP, indent);
            Log.e(Constants.TAG, "pgp error encoding key", e);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        } catch (IOException e) {
            Log.e(Constants.TAG, "io error encoding key", e);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        }

    }

    /** This method introduces a list of modifications specified by a SaveKeyringParcel to a
     * WrappedSecretKeyRing.
     *
     * This method relies on WrappedSecretKeyRing's canonicalization property!
     *
     * Note that PGPPublicKeyRings can not be directly modified. Instead, the corresponding
     * PGPSecretKeyRing must be modified and consequently consolidated with its public counterpart.
     * This is a natural workflow since pgp keyrings are immutable data structures: Old semantics
     * are changed by adding new certificates, which implicitly override older certificates.
     *
     * Note that this method does not care about any "special" type of master key. If unlocking
     * with a passphrase fails, the operation will fail with an unlocking error. More specific
     * handling of errors should be done in UI code!
     *
     * If the passphrase is null, only a restricted subset of operations will be available,
     * namely stripping of subkeys and changing the protection mode of dummy keys.
     *
     */
    public PgpEditKeyResult modifySecretKeyRing(CanonicalizedSecretKeyRing wsKR,
                                                CryptoInputParcel cryptoInput,
                                                SaveKeyringParcel saveParcel) {

        OperationLog log = new OperationLog();
        int indent = 0;

        /*
         * 1. Unlock private key
         * 2a. Add certificates for new user ids
         * 2b. Add revocations for revoked user ids
         * 3. If primary user id changed, generate new certificates for both old and new
         * 4a. For each subkey change, generate new subkey binding certificate
         * 4b. For each subkey revocation, generate new subkey revocation certificate
         * 5. Generate and add new subkeys
         * 6. If requested, change passphrase
         */

        log.add(LogType.MSG_MF, indent,
                KeyFormattingUtils.convertKeyIdToHex(wsKR.getMasterKeyId()));
        indent += 1;
        progress(R.string.progress_building_key, 0);

        // Make sure this is called with a proper SaveKeyringParcel
        if (saveParcel.mMasterKeyId == null || saveParcel.mMasterKeyId != wsKR.getMasterKeyId()) {
            log.add(LogType.MSG_MF_ERROR_KEYID, indent);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        }

        // We work on bouncycastle object level here
        PGPSecretKeyRing sKR = wsKR.getRing();
        PGPSecretKey masterSecretKey = sKR.getSecretKey();

        // Make sure the fingerprint matches
        if (saveParcel.mFingerprint == null || !Arrays.equals(saveParcel.mFingerprint,
                                    masterSecretKey.getPublicKey().getFingerprint())) {
            log.add(LogType.MSG_MF_ERROR_FINGERPRINT, indent);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        }

        if (saveParcel.isEmpty()) {
            log.add(LogType.MSG_MF_ERROR_NOOP, indent);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        }

        // Ensure we don't have multiple keys for the same slot.
        boolean hasSign = false;
        boolean hasEncrypt = false;
        boolean hasAuth = false;
        for(SaveKeyringParcel.SubkeyChange change : saveParcel.mChangeSubKeys) {
            if (change.mMoveKeyToCard) {
                // If this is a keytocard operation, see if it was completed: look for a hash
                // matching the given subkey ID in cryptoData.
                byte[] subKeyId = new byte[8];
                ByteBuffer buf = ByteBuffer.wrap(subKeyId);
                buf.putLong(change.mKeyId).rewind();

                byte[] serialNumber = cryptoInput.getCryptoData().get(buf);
                if (serialNumber != null) {
                    change.mMoveKeyToCard = false;
                    change.mDummyDivert = serialNumber;
                }
            }

            if (change.mMoveKeyToCard) {
                // Pending keytocard operation. Need to make sure that we don't have multiple
                // subkeys pending for the same slot.
                CanonicalizedSecretKey wsK = wsKR.getSecretKey(change.mKeyId);

                if ((wsK.canSign() || wsK.canCertify())) {
                    if (hasSign) {
                        log.add(LogType.MSG_MF_ERROR_DUPLICATE_KEYTOCARD_FOR_SLOT, indent + 1);
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    } else {
                        hasSign = true;
                    }
                } else if ((wsK.canEncrypt())) {
                    if (hasEncrypt) {
                        log.add(LogType.MSG_MF_ERROR_DUPLICATE_KEYTOCARD_FOR_SLOT, indent + 1);
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    } else {
                        hasEncrypt = true;
                    }
                } else if ((wsK.canAuthenticate())) {
                    if (hasAuth) {
                        log.add(LogType.MSG_MF_ERROR_DUPLICATE_KEYTOCARD_FOR_SLOT, indent + 1);
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    } else {
                        hasAuth = true;
                    }
                } else {
                    log.add(LogType.MSG_MF_ERROR_INVALID_FLAGS_FOR_KEYTOCARD, indent + 1);
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }
            }
        }

        if (isDummy(masterSecretKey) || saveParcel.isRestrictedOnly()) {
            log.add(LogType.MSG_MF_RESTRICTED_MODE, indent);
            return internalRestricted(sKR, saveParcel, log, indent + 1);
        }

        // Do we require a passphrase? If so, pass it along
        if (!isDivertToCard(masterSecretKey) && !cryptoInput.hasPassphrase()) {
            log.add(LogType.MSG_MF_REQUIRE_PASSPHRASE, indent);
            return new PgpEditKeyResult(log, RequiredInputParcel.createRequiredSignPassphrase(
                    masterSecretKey.getKeyID(), masterSecretKey.getKeyID(),
                    cryptoInput.getSignatureTime()));
        }

        // read masterKeyFlags, and use the same as before.
        // since this is the master key, this contains at least CERTIFY_OTHER
        PGPPublicKey masterPublicKey = masterSecretKey.getPublicKey();
        int masterKeyFlags = readKeyFlags(masterPublicKey) | KeyFlags.CERTIFY_OTHER;
        Date expiryTime = wsKR.getPublicKey().getExpiryTime();
        long masterKeyExpiry = expiryTime != null ? expiryTime.getTime() / 1000 : 0L;

        return internal(sKR, masterSecretKey, masterKeyFlags, masterKeyExpiry, cryptoInput, saveParcel, log, indent);

    }

    private PgpEditKeyResult internal(PGPSecretKeyRing sKR, PGPSecretKey masterSecretKey,
                                     int masterKeyFlags, long masterKeyExpiry,
                                     CryptoInputParcel cryptoInput,
                                     SaveKeyringParcel saveParcel,
                                     OperationLog log,
                                     int indent) {

        NfcSignOperationsBuilder nfcSignOps = new NfcSignOperationsBuilder(
                cryptoInput.getSignatureTime(), masterSecretKey.getKeyID(),
                masterSecretKey.getKeyID());
        NfcKeyToCardOperationsBuilder nfcKeyToCardOps = new NfcKeyToCardOperationsBuilder(
                masterSecretKey.getKeyID());

        progress(R.string.progress_modify, 0);

        PGPPublicKey masterPublicKey = masterSecretKey.getPublicKey();

        PGPPrivateKey masterPrivateKey;

        if (isDivertToCard(masterSecretKey)) {
            masterPrivateKey = null;
            log.add(LogType.MSG_MF_DIVERT, indent);
        } else {

            // 1. Unlock private key
            progress(R.string.progress_modify_unlock, 10);
            log.add(LogType.MSG_MF_UNLOCK, indent);
            {
                try {
                    PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(
                            Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(cryptoInput.getPassphrase().getCharArray());
                    masterPrivateKey = masterSecretKey.extractPrivateKey(keyDecryptor);
                } catch (PGPException e) {
                    log.add(LogType.MSG_MF_UNLOCK_ERROR, indent + 1);
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }
            }
        }

        try {

            // Check if we were cancelled
            if (checkCancelled()) {
                log.add(LogType.MSG_OPERATION_CANCELLED, indent);
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_CANCELLED, log, null);
            }

            { // work on master secret key

                PGPPublicKey modifiedPublicKey = masterPublicKey;

                // 2a. Add certificates for new user ids
                subProgressPush(15, 23);
                for (int i = 0; i < saveParcel.mAddUserIds.size(); i++) {

                    progress(R.string.progress_modify_adduid, (i - 1) * (100 / saveParcel.mAddUserIds.size()));
                    String userId = saveParcel.mAddUserIds.get(i);
                    log.add(LogType.MSG_MF_UID_ADD, indent, userId);

                    if ("".equals(userId)) {
                        log.add(LogType.MSG_MF_UID_ERROR_EMPTY, indent + 1);
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    }

                    // this operation supersedes all previous binding and revocation certificates,
                    // so remove those to retain assertions from canonicalization for later operations
                    @SuppressWarnings("unchecked")
                    Iterator<PGPSignature> it = modifiedPublicKey.getSignaturesForID(userId);
                    if (it != null) {
                        for (PGPSignature cert : new IterableIterator<>(it)) {
                            if (cert.getKeyID() != masterPublicKey.getKeyID()) {
                                // foreign certificate?! error error error
                                log.add(LogType.MSG_MF_ERROR_INTEGRITY, indent);
                                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                            }
                            if (cert.getSignatureType() == PGPSignature.CERTIFICATION_REVOCATION
                                    || cert.getSignatureType() == PGPSignature.NO_CERTIFICATION
                                    || cert.getSignatureType() == PGPSignature.CASUAL_CERTIFICATION
                                    || cert.getSignatureType() == PGPSignature.POSITIVE_CERTIFICATION
                                    || cert.getSignatureType() == PGPSignature.DEFAULT_CERTIFICATION) {
                                modifiedPublicKey = PGPPublicKey.removeCertification(
                                        modifiedPublicKey, userId, cert);
                            }
                        }
                    }

                    // if it's supposed to be primary, we can do that here as well
                    boolean isPrimary = saveParcel.mChangePrimaryUserId != null
                            && userId.equals(saveParcel.mChangePrimaryUserId);
                    // generate and add new certificate
                    try {
                        PGPSignature cert = generateUserIdSignature(
                                getSignatureGenerator(masterSecretKey, cryptoInput),
                                cryptoInput.getSignatureTime(),
                                masterPrivateKey, masterPublicKey, userId,
                                isPrimary, masterKeyFlags, masterKeyExpiry);
                        modifiedPublicKey = PGPPublicKey.addCertification(modifiedPublicKey, userId, cert);
                    } catch (NfcInteractionNeeded e) {
                        nfcSignOps.addHash(e.hashToSign, e.hashAlgo);
                    }
                }
                subProgressPop();

                // 2b. Add certificates for new user ids
                subProgressPush(23, 32);
                for (int i = 0; i < saveParcel.mAddUserAttribute.size(); i++) {

                    progress(R.string.progress_modify_adduat, (i - 1) * (100 / saveParcel.mAddUserAttribute.size()));
                    WrappedUserAttribute attribute = saveParcel.mAddUserAttribute.get(i);

                    switch (attribute.getType()) {
                        // the 'none' type must not succeed
                        case WrappedUserAttribute.UAT_NONE:
                            log.add(LogType.MSG_MF_UAT_ERROR_EMPTY, indent);
                            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                        case WrappedUserAttribute.UAT_IMAGE:
                            log.add(LogType.MSG_MF_UAT_ADD_IMAGE, indent);
                            break;
                        default:
                            log.add(LogType.MSG_MF_UAT_ADD_UNKNOWN, indent);
                            break;
                    }

                    PGPUserAttributeSubpacketVector vector = attribute.getVector();

                    // generate and add new certificate
                    try {
                        PGPSignature cert = generateUserAttributeSignature(
                                getSignatureGenerator(masterSecretKey, cryptoInput),
                                cryptoInput.getSignatureTime(),
                                masterPrivateKey, masterPublicKey, vector,
                                masterKeyFlags, masterKeyExpiry);
                        modifiedPublicKey = PGPPublicKey.addCertification(modifiedPublicKey, vector, cert);
                    } catch (NfcInteractionNeeded e) {
                        nfcSignOps.addHash(e.hashToSign, e.hashAlgo);
                    }
                }
                subProgressPop();

                // 2c. Add revocations for revoked user ids
                subProgressPush(32, 40);
                for (int i = 0; i < saveParcel.mRevokeUserIds.size(); i++) {

                    progress(R.string.progress_modify_revokeuid, (i - 1) * (100 / saveParcel.mRevokeUserIds.size()));
                    String userId = saveParcel.mRevokeUserIds.get(i);
                    log.add(LogType.MSG_MF_UID_REVOKE, indent, userId);

                    // Make sure the user id exists (yes these are 10 LoC in Java!)
                    boolean exists = false;
                    //noinspection unchecked
                    for (String uid : new IterableIterator<String>(modifiedPublicKey.getUserIDs())) {
                        if (userId.equals(uid)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        log.add(LogType.MSG_MF_ERROR_NOEXIST_REVOKE, indent);
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    }

                    // a duplicate revocation will be removed during canonicalization, so no need to
                    // take care of that here.
                    try {
                        PGPSignature cert = generateRevocationSignature(
                                getSignatureGenerator(masterSecretKey, cryptoInput),
                                cryptoInput.getSignatureTime(),
                                masterPrivateKey, masterPublicKey, userId);
                        modifiedPublicKey = PGPPublicKey.addCertification(modifiedPublicKey, userId, cert);
                    } catch (NfcInteractionNeeded e) {
                        nfcSignOps.addHash(e.hashToSign, e.hashAlgo);
                    }
                }
                subProgressPop();

                // 3. If primary user id changed, generate new certificates for both old and new
                if (saveParcel.mChangePrimaryUserId != null) {
                    progress(R.string.progress_modify_primaryuid, 40);

                    // keep track if we actually changed one
                    boolean ok = false;
                    log.add(LogType.MSG_MF_UID_PRIMARY, indent, saveParcel.mChangePrimaryUserId);
                    indent += 1;

                    // we work on the modifiedPublicKey here, to respect new or newly revoked uids
                    // noinspection unchecked
                    for (String userId : new IterableIterator<String>(modifiedPublicKey.getUserIDs())) {
                        boolean isRevoked = false;
                        PGPSignature currentCert = null;
                        // noinspection unchecked
                        for (PGPSignature cert : new IterableIterator<PGPSignature>(
                                modifiedPublicKey.getSignaturesForID(userId))) {
                            if (cert.getKeyID() != masterPublicKey.getKeyID()) {
                                // foreign certificate?! error error error
                                log.add(LogType.MSG_MF_ERROR_INTEGRITY, indent);
                                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                            }
                            // we know from canonicalization that if there is any revocation here, it
                            // is valid and not superseded by a newer certification.
                            if (cert.getSignatureType() == PGPSignature.CERTIFICATION_REVOCATION) {
                                isRevoked = true;
                                continue;
                            }
                            // we know from canonicalization that there is only one binding
                            // certification here, so we can just work with the first one.
                            if (cert.getSignatureType() == PGPSignature.NO_CERTIFICATION ||
                                    cert.getSignatureType() == PGPSignature.CASUAL_CERTIFICATION ||
                                    cert.getSignatureType() == PGPSignature.POSITIVE_CERTIFICATION ||
                                    cert.getSignatureType() == PGPSignature.DEFAULT_CERTIFICATION) {
                                currentCert = cert;
                            }
                        }

                        if (currentCert == null) {
                            // no certificate found?! error error error
                            log.add(LogType.MSG_MF_ERROR_INTEGRITY, indent);
                            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                        }

                        // we definitely should not update certifications of revoked keys, so just leave it.
                        if (isRevoked) {
                            // revoked user ids cannot be primary!
                            if (userId.equals(saveParcel.mChangePrimaryUserId)) {
                                log.add(LogType.MSG_MF_ERROR_REVOKED_PRIMARY, indent);
                                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                            }
                            continue;
                        }

                        // if this is~ the/a primary user id
                        if (currentCert.getHashedSubPackets() != null
                                && currentCert.getHashedSubPackets().isPrimaryUserID()) {
                            // if it's the one we want, just leave it as is
                            if (userId.equals(saveParcel.mChangePrimaryUserId)) {
                                ok = true;
                                continue;
                            }
                            // otherwise, generate new non-primary certification
                            log.add(LogType.MSG_MF_PRIMARY_REPLACE_OLD, indent);
                            modifiedPublicKey = PGPPublicKey.removeCertification(
                                    modifiedPublicKey, userId, currentCert);
                            try {
                                PGPSignature newCert = generateUserIdSignature(
                                        getSignatureGenerator(masterSecretKey, cryptoInput),
                                        cryptoInput.getSignatureTime(),
                                        masterPrivateKey, masterPublicKey, userId, false,
                                        masterKeyFlags, masterKeyExpiry);
                                modifiedPublicKey = PGPPublicKey.addCertification(
                                        modifiedPublicKey, userId, newCert);
                            } catch (NfcInteractionNeeded e) {
                                nfcSignOps.addHash(e.hashToSign, e.hashAlgo);
                            }

                            continue;
                        }

                        // if we are here, this is not currently a primary user id

                        // if it should be
                        if (userId.equals(saveParcel.mChangePrimaryUserId)) {
                            // add shiny new primary user id certificate
                            log.add(LogType.MSG_MF_PRIMARY_NEW, indent);
                            modifiedPublicKey = PGPPublicKey.removeCertification(
                                    modifiedPublicKey, userId, currentCert);
                            try {
                                PGPSignature newCert = generateUserIdSignature(
                                        getSignatureGenerator(masterSecretKey, cryptoInput),
                                        cryptoInput.getSignatureTime(),
                                        masterPrivateKey, masterPublicKey, userId, true,
                                        masterKeyFlags, masterKeyExpiry);
                                modifiedPublicKey = PGPPublicKey.addCertification(
                                        modifiedPublicKey, userId, newCert);
                            } catch (NfcInteractionNeeded e) {
                                nfcSignOps.addHash(e.hashToSign, e.hashAlgo);
                            }
                            ok = true;
                        }

                        // user id is not primary and is not supposed to be - nothing to do here.

                    }

                    indent -= 1;

                    if (!ok) {
                        log.add(LogType.MSG_MF_ERROR_NOEXIST_PRIMARY, indent);
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    }
                }

                // Update the secret key ring
                if (modifiedPublicKey != masterPublicKey) {
                    masterSecretKey = PGPSecretKey.replacePublicKey(masterSecretKey, modifiedPublicKey);
                    masterPublicKey = modifiedPublicKey;
                    sKR = PGPSecretKeyRing.insertSecretKey(sKR, masterSecretKey);
                }

            }

            // Check if we were cancelled - again
            if (checkCancelled()) {
                log.add(LogType.MSG_OPERATION_CANCELLED, indent);
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_CANCELLED, log, null);
            }

            // 4a. For each subkey change, generate new subkey binding certificate
            subProgressPush(50, 60);
            for (int i = 0; i < saveParcel.mChangeSubKeys.size(); i++) {

                progress(R.string.progress_modify_subkeychange, (i-1) * (100 / saveParcel.mChangeSubKeys.size()));
                SaveKeyringParcel.SubkeyChange change = saveParcel.mChangeSubKeys.get(i);
                log.add(LogType.MSG_MF_SUBKEY_CHANGE,
                        indent, KeyFormattingUtils.convertKeyIdToHex(change.mKeyId));

                PGPSecretKey sKey = sKR.getSecretKey(change.mKeyId);
                if (sKey == null) {
                    log.add(LogType.MSG_MF_ERROR_SUBKEY_MISSING,
                            indent + 1, KeyFormattingUtils.convertKeyIdToHex(change.mKeyId));
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }

                if (change.mDummyStrip) {
                    // IT'S DANGEROUS~
                    // no really, it is. this operation irrevocably removes the private key data from the key
                    sKey = PGPSecretKey.constructGnuDummyKey(sKey.getPublicKey());
                    sKR = PGPSecretKeyRing.insertSecretKey(sKR, sKey);
                } else if (change.mMoveKeyToCard) {
                    if (checkSmartCardCompatibility(sKey, log, indent + 1)) {
                        log.add(LogType.MSG_MF_KEYTOCARD_START, indent + 1,
                                KeyFormattingUtils.convertKeyIdToHex(change.mKeyId));
                        nfcKeyToCardOps.addSubkey(change.mKeyId);
                    } else {
                        // Appropriate log message already set by checkSmartCardCompatibility
                        return new PgpEditKeyResult(EditKeyResult.RESULT_ERROR, log, null);
                    }
                } else if (change.mDummyDivert != null) {
                    // NOTE: Does this code get executed? Or always handled in internalRestricted?
                    if (change.mDummyDivert.length != 16) {
                        log.add(LogType.MSG_MF_ERROR_DIVERT_SERIAL,
                                indent + 1, KeyFormattingUtils.convertKeyIdToHex(change.mKeyId));
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    }
                    log.add(LogType.MSG_MF_KEYTOCARD_FINISH, indent + 1,
                            KeyFormattingUtils.convertKeyIdToHex(change.mKeyId),
                            Hex.toHexString(change.mDummyDivert, 8, 6));
                    sKey = PGPSecretKey.constructGnuDummyKey(sKey.getPublicKey(), change.mDummyDivert);
                    sKR = PGPSecretKeyRing.insertSecretKey(sKR, sKey);
                }



                // This doesn't concern us any further
                if (!change.mRecertify && (change.mExpiry == null && change.mFlags == null)) {
                    continue;
                }

                // expiry must not be in the past
                if (change.mExpiry != null && change.mExpiry != 0 &&
                        new Date(change.mExpiry*1000).before(new Date())) {
                    log.add(LogType.MSG_MF_ERROR_PAST_EXPIRY,
                            indent + 1, KeyFormattingUtils.convertKeyIdToHex(change.mKeyId));
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }

                // if this is the master key, update uid certificates instead
                if (change.mKeyId == masterPublicKey.getKeyID()) {
                    int flags = change.mFlags == null ? masterKeyFlags : change.mFlags;
                    long expiry = change.mExpiry == null ? masterKeyExpiry : change.mExpiry;

                    if ((flags & KeyFlags.CERTIFY_OTHER) != KeyFlags.CERTIFY_OTHER) {
                        log.add(LogType.MSG_MF_ERROR_NO_CERTIFY, indent + 1);
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    }

                    PGPPublicKey pKey =
                            updateMasterCertificates(
                                    masterSecretKey, masterPrivateKey, masterPublicKey,
                                    flags, expiry, cryptoInput,  nfcSignOps, indent, log);
                    if (pKey == null) {
                        // error log entry has already been added by updateMasterCertificates itself
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    }
                    masterSecretKey = PGPSecretKey.replacePublicKey(sKey, pKey);
                    masterPublicKey = pKey;
                    sKR = PGPSecretKeyRing.insertSecretKey(sKR, masterSecretKey);
                    continue;
                }

                // otherwise, continue working on the public key
                PGPPublicKey pKey = sKey.getPublicKey();

                // keep old flags, or replace with new ones
                int flags = change.mFlags == null ? readKeyFlags(pKey) : change.mFlags;
                long expiry;
                if (change.mExpiry == null) {
                    long valid = pKey.getValidSeconds();
                    expiry = valid == 0
                            ? 0
                            : pKey.getCreationTime().getTime() / 1000 + pKey.getValidSeconds();
                } else {
                    expiry = change.mExpiry;
                }

                // drop all old signatures, they will be superseded by the new one
                //noinspection unchecked
                for (PGPSignature sig : new IterableIterator<PGPSignature>(pKey.getSignatures())) {
                    // special case: if there is a revocation, don't use expiry from before
                    if ( (change.mExpiry == null || change.mExpiry == 0L)
                            && sig.getSignatureType() == PGPSignature.SUBKEY_REVOCATION) {
                        expiry = 0;
                    }
                    pKey = PGPPublicKey.removeCertification(pKey, sig);
                }

                PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder()
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                                cryptoInput.getPassphrase().getCharArray());
                PGPPrivateKey subPrivateKey = sKey.extractPrivateKey(keyDecryptor);
                PGPSignature sig = generateSubkeyBindingSignature(
                        getSignatureGenerator(masterSecretKey, cryptoInput),
                        cryptoInput.getSignatureTime(),
                        masterPublicKey, masterPrivateKey, subPrivateKey, pKey, flags, expiry);

                // generate and add new signature
                pKey = PGPPublicKey.addCertification(pKey, sig);
                sKR = PGPSecretKeyRing.insertSecretKey(sKR, PGPSecretKey.replacePublicKey(sKey, pKey));
            }
            subProgressPop();

            // 4b. For each subkey revocation, generate new subkey revocation certificate
            subProgressPush(60, 65);
            for (int i = 0; i < saveParcel.mRevokeSubKeys.size(); i++) {

                progress(R.string.progress_modify_subkeyrevoke, (i-1) * (100 / saveParcel.mRevokeSubKeys.size()));
                long revocation = saveParcel.mRevokeSubKeys.get(i);
                log.add(LogType.MSG_MF_SUBKEY_REVOKE,
                        indent, KeyFormattingUtils.convertKeyIdToHex(revocation));

                PGPSecretKey sKey = sKR.getSecretKey(revocation);
                if (sKey == null) {
                    log.add(LogType.MSG_MF_ERROR_SUBKEY_MISSING,
                            indent+1, KeyFormattingUtils.convertKeyIdToHex(revocation));
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }
                PGPPublicKey pKey = sKey.getPublicKey();

                // generate and add new signature
                try {
                    PGPSignature sig = generateRevocationSignature(
                            getSignatureGenerator(masterSecretKey, cryptoInput),
                            cryptoInput.getSignatureTime(),
                            masterPublicKey, masterPrivateKey, pKey);

                    pKey = PGPPublicKey.addCertification(pKey, sig);
                    sKR = PGPSecretKeyRing.insertSecretKey(sKR, PGPSecretKey.replacePublicKey(sKey, pKey));
                } catch (NfcInteractionNeeded e) {
                    nfcSignOps.addHash(e.hashToSign, e.hashAlgo);
                }
            }
            subProgressPop();

            // 5. Generate and add new subkeys
            subProgressPush(70, 90);
            for (int i = 0; i < saveParcel.mAddSubKeys.size(); i++) {

                // Check if we were cancelled - again. This operation is expensive so we do it each loop.
                if (checkCancelled()) {
                    log.add(LogType.MSG_OPERATION_CANCELLED, indent);
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_CANCELLED, log, null);
                }

                progress(R.string.progress_modify_subkeyadd, (i-1) * (100 / saveParcel.mAddSubKeys.size()));
                SaveKeyringParcel.SubkeyAdd add = saveParcel.mAddSubKeys.get(i);
                log.add(LogType.MSG_MF_SUBKEY_NEW, indent,
                        KeyFormattingUtils.getAlgorithmInfo(add.mAlgorithm, add.mKeySize, add.mCurve) );

                if (add.mExpiry == null) {
                    log.add(LogType.MSG_MF_ERROR_NULL_EXPIRY, indent +1);
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }

                if (add.mExpiry > 0L && new Date(add.mExpiry*1000).before(new Date())) {
                    log.add(LogType.MSG_MF_ERROR_PAST_EXPIRY, indent +1);
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }

                // generate a new secret key (privkey only for now)
                subProgressPush(
                    (i-1) * (100 / saveParcel.mAddSubKeys.size()),
                    i * (100 / saveParcel.mAddSubKeys.size())
                );
                PGPKeyPair keyPair = createKey(add, cryptoInput.getSignatureTime(), log, indent);
                subProgressPop();
                if (keyPair == null) {
                    log.add(LogType.MSG_MF_ERROR_PGP, indent +1);
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }

                // add subkey binding signature (making this a sub rather than master key)
                PGPPublicKey pKey = keyPair.getPublicKey();
                try {
                    PGPSignature cert = generateSubkeyBindingSignature(
                            getSignatureGenerator(masterSecretKey, cryptoInput),
                            cryptoInput.getSignatureTime(),
                            masterPublicKey, masterPrivateKey, keyPair.getPrivateKey(), pKey,
                            add.mFlags, add.mExpiry);
                    pKey = PGPPublicKey.addSubkeyBindingCertification(pKey, cert);
                } catch (NfcInteractionNeeded e) {
                    nfcSignOps.addHash(e.hashToSign, e.hashAlgo);
                }

                PGPSecretKey sKey; {
                    // Build key encrypter and decrypter based on passphrase
                    PGPDigestCalculator encryptorHashCalc = new JcaPGPDigestCalculatorProviderBuilder()
                            .build().get(PgpConstants.SECRET_KEY_ENCRYPTOR_HASH_ALGO);
                    PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                            PgpConstants.SECRET_KEY_ENCRYPTOR_SYMMETRIC_ALGO, encryptorHashCalc,
                            PgpConstants.SECRET_KEY_ENCRYPTOR_S2K_COUNT)
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                                    cryptoInput.getPassphrase().getCharArray());

                    PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder()
                            .build().get(PgpConstants.SECRET_KEY_SIGNATURE_CHECKSUM_HASH_ALGO);
                    sKey = new PGPSecretKey(keyPair.getPrivateKey(), pKey, sha1Calc, false, keyEncryptor);
                }

                log.add(LogType.MSG_MF_SUBKEY_NEW_ID,
                        indent+1, KeyFormattingUtils.convertKeyIdToHex(sKey.getKeyID()));

                sKR = PGPSecretKeyRing.insertSecretKey(sKR, sKey);

            }
            subProgressPop();

            // Check if we were cancelled - again. This operation is expensive so we do it each loop.
            if (checkCancelled()) {
                log.add(LogType.MSG_OPERATION_CANCELLED, indent);
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_CANCELLED, log, null);
            }

            // 6. If requested, change passphrase
            if (saveParcel.mNewUnlock != null) {
                progress(R.string.progress_modify_passphrase, 90);
                log.add(LogType.MSG_MF_PASSPHRASE, indent);
                indent += 1;

                sKR = applyNewUnlock(sKR, masterPublicKey, masterPrivateKey,
                        cryptoInput.getPassphrase(), saveParcel.mNewUnlock, log, indent);
                if (sKR == null) {
                    // The error has been logged above, just return a bad state
                    return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                }

                indent -= 1;
            }

        } catch (IOException e) {
            Log.e(Constants.TAG, "encountered IOException while modifying key", e);
            log.add(LogType.MSG_MF_ERROR_ENCODE, indent+1);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        } catch (PGPException e) {
            Log.e(Constants.TAG, "encountered pgp error while modifying key", e);
            log.add(LogType.MSG_MF_ERROR_PGP, indent+1);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        } catch (SignatureException e) {
            Log.e(Constants.TAG, "encountered SignatureException while modifying key", e);
            log.add(LogType.MSG_MF_ERROR_SIG, indent+1);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        }

        progress(R.string.progress_done, 100);

        if (!nfcSignOps.isEmpty() && !nfcKeyToCardOps.isEmpty()) {
            log.add(LogType.MSG_MF_ERROR_CONFLICTING_NFC_COMMANDS, indent+1);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        }

        if (!nfcSignOps.isEmpty()) {
            log.add(LogType.MSG_MF_REQUIRE_DIVERT, indent);
            return new PgpEditKeyResult(log, nfcSignOps.build());
        }

        if (!nfcKeyToCardOps.isEmpty()) {
            log.add(LogType.MSG_MF_REQUIRE_DIVERT, indent);
            return new PgpEditKeyResult(log, nfcKeyToCardOps.build());
        }

        log.add(LogType.MSG_MF_SUCCESS, indent);
        return new PgpEditKeyResult(OperationResult.RESULT_OK, log, new UncachedKeyRing(sKR));

    }

    /** This method does the actual modifications in a keyring just like internal, except it
     * supports only the subset of operations which require no passphrase, and will error
     * otherwise.
     */
    private PgpEditKeyResult internalRestricted(PGPSecretKeyRing sKR, SaveKeyringParcel saveParcel,
                                                OperationLog log, int indent) {

        progress(R.string.progress_modify, 0);

        // Make sure the saveParcel includes only operations available without passphrae!
        if (!saveParcel.isRestrictedOnly()) {
            log.add(LogType.MSG_MF_ERROR_RESTRICTED, indent);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
        }

        // Check if we were cancelled
        if (checkCancelled()) {
            log.add(LogType.MSG_OPERATION_CANCELLED, indent);
            return new PgpEditKeyResult(PgpEditKeyResult.RESULT_CANCELLED, log, null);
        }

        // The only operation we can do here:
        // 4a. Strip secret keys, or change their protection mode (stripped/divert-to-card)
        subProgressPush(50, 60);
        for (int i = 0; i < saveParcel.mChangeSubKeys.size(); i++) {

            progress(R.string.progress_modify_subkeychange, (i - 1) * (100 / saveParcel.mChangeSubKeys.size()));
            SaveKeyringParcel.SubkeyChange change = saveParcel.mChangeSubKeys.get(i);
            log.add(LogType.MSG_MF_SUBKEY_CHANGE,
                    indent, KeyFormattingUtils.convertKeyIdToHex(change.mKeyId));

            PGPSecretKey sKey = sKR.getSecretKey(change.mKeyId);
            if (sKey == null) {
                log.add(LogType.MSG_MF_ERROR_SUBKEY_MISSING,
                        indent + 1, KeyFormattingUtils.convertKeyIdToHex(change.mKeyId));
                return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
            }

            if (change.mDummyStrip || change.mDummyDivert != null) {
                // IT'S DANGEROUS~
                // no really, it is. this operation irrevocably removes the private key data from the key
                if (change.mDummyStrip) {
                    sKey = PGPSecretKey.constructGnuDummyKey(sKey.getPublicKey());
                } else {
                    // the serial number must be 16 bytes in length
                    if (change.mDummyDivert.length != 16) {
                        log.add(LogType.MSG_MF_ERROR_DIVERT_SERIAL,
                                indent + 1, KeyFormattingUtils.convertKeyIdToHex(change.mKeyId));
                        return new PgpEditKeyResult(PgpEditKeyResult.RESULT_ERROR, log, null);
                    }
                    log.add(LogType.MSG_MF_KEYTOCARD_FINISH, indent + 1,
                            KeyFormattingUtils.convertKeyIdToHex(change.mKeyId),
                            Hex.toHexString(change.mDummyDivert, 8, 6));
                    sKey = PGPSecretKey.constructGnuDummyKey(sKey.getPublicKey(), change.mDummyDivert);
                }
                sKR = PGPSecretKeyRing.insertSecretKey(sKR, sKey);
            }

        }

        // And we're done!
        progress(R.string.progress_done, 100);
        log.add(LogType.MSG_MF_SUCCESS, indent);
        return new PgpEditKeyResult(OperationResult.RESULT_OK, log, new UncachedKeyRing(sKR));

    }


    private static PGPSecretKeyRing applyNewUnlock(
            PGPSecretKeyRing sKR,
            PGPPublicKey masterPublicKey,
            PGPPrivateKey masterPrivateKey,
            Passphrase passphrase,
            ChangeUnlockParcel newUnlock,
            OperationLog log, int indent) throws PGPException {

        if (newUnlock.mNewPassphrase != null) {
            sKR = applyNewPassphrase(sKR, masterPublicKey, passphrase, newUnlock.mNewPassphrase, log, indent);

            // if there is any old packet with notation data
            if (hasNotationData(sKR)) {

                log.add(LogType.MSG_MF_NOTATION_EMPTY, indent);

                // add packet with EMPTY notation data (updates old one, but will be stripped later)
                PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                        masterPrivateKey.getPublicKeyPacket().getAlgorithm(),
                        PgpConstants.SECRET_KEY_SIGNATURE_HASH_ALGO)
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);
                { // set subpackets
                    PGPSignatureSubpacketGenerator hashedPacketsGen = new PGPSignatureSubpacketGenerator();
                    hashedPacketsGen.setExportable(false, false);
                    sGen.setHashedSubpackets(hashedPacketsGen.generate());
                }
                sGen.init(PGPSignature.DIRECT_KEY, masterPrivateKey);
                PGPSignature emptySig = sGen.generateCertification(masterPublicKey);

                masterPublicKey = PGPPublicKey.addCertification(masterPublicKey, emptySig);
                sKR = PGPSecretKeyRing.insertSecretKey(sKR,
                        PGPSecretKey.replacePublicKey(sKR.getSecretKey(), masterPublicKey));
            }

            return sKR;
        }

        if (newUnlock.mNewPin != null) {
            sKR = applyNewPassphrase(sKR, masterPublicKey, passphrase, newUnlock.mNewPin, log, indent);

            log.add(LogType.MSG_MF_NOTATION_PIN, indent);

            // add packet with "pin" notation data
            PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                    masterPrivateKey.getPublicKeyPacket().getAlgorithm(),
                    PgpConstants.SECRET_KEY_SIGNATURE_HASH_ALGO)
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
            PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);
            { // set subpackets
                PGPSignatureSubpacketGenerator hashedPacketsGen = new PGPSignatureSubpacketGenerator();
                hashedPacketsGen.setExportable(false, false);
                hashedPacketsGen.setNotationData(false, true, "unlock.pin@sufficientlysecure.org", "1");
                sGen.setHashedSubpackets(hashedPacketsGen.generate());
            }
            sGen.init(PGPSignature.DIRECT_KEY, masterPrivateKey);
            PGPSignature emptySig = sGen.generateCertification(masterPublicKey);

            masterPublicKey = PGPPublicKey.addCertification(masterPublicKey, emptySig);
            sKR = PGPSecretKeyRing.insertSecretKey(sKR,
                    PGPSecretKey.replacePublicKey(sKR.getSecretKey(), masterPublicKey));

            return sKR;
        }

        throw new UnsupportedOperationException("PIN passphrases not yet implemented!");

    }

    /** This method returns true iff the provided keyring has a local direct key signature
     * with notation data.
     */
    private static boolean hasNotationData(PGPSecretKeyRing sKR) {
        // noinspection unchecked
        Iterator<PGPSignature> sigs = sKR.getPublicKey().getKeySignatures();
        while (sigs.hasNext()) {
            WrappedSignature sig = new WrappedSignature(sigs.next());
            if (sig.getSignatureType() == PGPSignature.DIRECT_KEY
                    && sig.isLocal() && !sig.getNotation().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static PGPSecretKeyRing applyNewPassphrase(
            PGPSecretKeyRing sKR,
            PGPPublicKey masterPublicKey,
            Passphrase passphrase,
            Passphrase newPassphrase,
            OperationLog log, int indent) throws PGPException {

        PGPDigestCalculator encryptorHashCalc = new JcaPGPDigestCalculatorProviderBuilder().build()
                .get(PgpConstants.SECRET_KEY_ENCRYPTOR_HASH_ALGO);
        PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(
                Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(passphrase.getCharArray());
        // Build key encryptor based on new passphrase
        PBESecretKeyEncryptor keyEncryptorNew = new JcePBESecretKeyEncryptorBuilder(
                PgpConstants.SECRET_KEY_ENCRYPTOR_SYMMETRIC_ALGO, encryptorHashCalc,
                PgpConstants.SECRET_KEY_ENCRYPTOR_S2K_COUNT)
                .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(newPassphrase.getCharArray());

        // noinspection unchecked
        for (PGPSecretKey sKey : new IterableIterator<PGPSecretKey>(sKR.getSecretKeys())) {
            log.add(LogType.MSG_MF_PASSPHRASE_KEY, indent,
                    KeyFormattingUtils.convertKeyIdToHex(sKey.getKeyID()));

            boolean ok = false;

            try {
                // try to set new passphrase
                sKey = PGPSecretKey.copyWithNewPassword(sKey, keyDecryptor, keyEncryptorNew);
                ok = true;
            } catch (PGPException e) {

                // if this is the master key, error!
                if (sKey.getKeyID() == masterPublicKey.getKeyID()) {
                    log.add(LogType.MSG_MF_ERROR_PASSPHRASE_MASTER, indent+1);
                    return null;
                }

                // being in here means decrypt failed, likely due to a bad passphrase try
                // again with an empty passphrase, maybe we can salvage this
                try {
                    log.add(LogType.MSG_MF_PASSPHRASE_EMPTY_RETRY, indent+1);
                    PBESecretKeyDecryptor emptyDecryptor =
                            new JcePBESecretKeyDecryptorBuilder().setProvider(
                                    Constants.BOUNCY_CASTLE_PROVIDER_NAME).build("".toCharArray());
                    sKey = PGPSecretKey.copyWithNewPassword(sKey, emptyDecryptor, keyEncryptorNew);
                    ok = true;
                } catch (PGPException e2) {
                    // non-fatal but not ok, handled below
                }
            }

            if (!ok) {
                // for a subkey, it's merely a warning
                log.add(LogType.MSG_MF_PASSPHRASE_FAIL, indent+1,
                        KeyFormattingUtils.convertKeyIdToHex(sKey.getKeyID()));
                continue;
            }

            sKR = PGPSecretKeyRing.insertSecretKey(sKR, sKey);

        }

        return sKR;

    }

    /** Update all (non-revoked) uid signatures with new flags and expiry time. */
    private PGPPublicKey updateMasterCertificates(
            PGPSecretKey masterSecretKey, PGPPrivateKey masterPrivateKey,
            PGPPublicKey masterPublicKey,
            int flags, long expiry,
            CryptoInputParcel cryptoInput,
            NfcSignOperationsBuilder nfcSignOps,
            int indent, OperationLog log)
            throws PGPException, IOException, SignatureException {

        // keep track if we actually changed one
        boolean ok = false;
        log.add(LogType.MSG_MF_MASTER, indent);
        indent += 1;

        PGPPublicKey modifiedPublicKey = masterPublicKey;

        // we work on the modifiedPublicKey here, to respect new or newly revoked uids
        // noinspection unchecked
        for (String userId : new IterableIterator<String>(modifiedPublicKey.getUserIDs())) {
            boolean isRevoked = false;
            PGPSignature currentCert = null;
            // noinspection unchecked
            for (PGPSignature cert : new IterableIterator<PGPSignature>(
                    modifiedPublicKey.getSignaturesForID(userId))) {
                if (cert.getKeyID() != masterPublicKey.getKeyID()) {
                    // foreign certificate?! error error error
                    log.add(LogType.MSG_MF_ERROR_INTEGRITY, indent);
                    return null;
                }
                // we know from canonicalization that if there is any revocation here, it
                // is valid and not superseded by a newer certification.
                if (cert.getSignatureType() == PGPSignature.CERTIFICATION_REVOCATION) {
                    isRevoked = true;
                    continue;
                }
                // we know from canonicalization that there is only one binding
                // certification here, so we can just work with the first one.
                if (cert.getSignatureType() == PGPSignature.NO_CERTIFICATION ||
                        cert.getSignatureType() == PGPSignature.CASUAL_CERTIFICATION ||
                        cert.getSignatureType() == PGPSignature.POSITIVE_CERTIFICATION ||
                        cert.getSignatureType() == PGPSignature.DEFAULT_CERTIFICATION) {
                    currentCert = cert;
                }
            }

            if (currentCert == null) {
                // no certificate found?! error error error
                log.add(LogType.MSG_MF_ERROR_INTEGRITY, indent);
                return null;
            }

            // we definitely should not update certifications of revoked keys, so just leave it.
            if (isRevoked) {
                continue;
            }

            // add shiny new user id certificate
            boolean isPrimary = currentCert.getHashedSubPackets() != null &&
                    currentCert.getHashedSubPackets().isPrimaryUserID();
            modifiedPublicKey = PGPPublicKey.removeCertification(
                    modifiedPublicKey, userId, currentCert);
            try {
                PGPSignature newCert = generateUserIdSignature(
                        getSignatureGenerator(masterSecretKey, cryptoInput),
                        cryptoInput.getSignatureTime(),
                        masterPrivateKey, masterPublicKey, userId, isPrimary, flags, expiry);
                modifiedPublicKey = PGPPublicKey.addCertification(
                        modifiedPublicKey, userId, newCert);
            } catch (NfcInteractionNeeded e) {
                nfcSignOps.addHash(e.hashToSign, e.hashAlgo);
            }
            ok = true;

        }

        if (!ok) {
            // might happen, theoretically, if there is a key with no uid..
            log.add(LogType.MSG_MF_ERROR_MASTER_NONE, indent);
            return null;
        }

        return modifiedPublicKey;

    }

    static PGPSignatureGenerator getSignatureGenerator(
            PGPSecretKey secretKey, CryptoInputParcel cryptoInput) {

        PGPContentSignerBuilder builder;

        S2K s2k = secretKey.getS2K();
        if (s2k != null && s2k.getType() == S2K.GNU_DUMMY_S2K
                && s2k.getProtectionMode() == S2K.GNU_PROTECTION_MODE_DIVERT_TO_CARD) {
            // use synchronous "NFC based" SignerBuilder
            builder = new NfcSyncPGPContentSignerBuilder(
                    secretKey.getPublicKey().getAlgorithm(),
                    PgpConstants.SECRET_KEY_SIGNATURE_HASH_ALGO,
                    secretKey.getKeyID(), cryptoInput.getCryptoData())
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
        } else {
            // content signer based on signing key algorithm and chosen hash algorithm
            builder = new JcaPGPContentSignerBuilder(
                    secretKey.getPublicKey().getAlgorithm(),
                    PgpConstants.SECRET_KEY_SIGNATURE_HASH_ALGO)
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
        }

        return new PGPSignatureGenerator(builder);

    }

    private static PGPSignatureSubpacketGenerator generateHashedSelfSigSubpackets(
            Date creationTime, PGPPublicKey pKey, boolean primary, int flags, long expiry
    ) {

        PGPSignatureSubpacketGenerator hashedPacketsGen = new PGPSignatureSubpacketGenerator();
        {
            /*
             * From RFC about critical subpackets:
             * If a subpacket is encountered that is
             * marked critical but is unknown to the evaluating software, the
             * evaluator SHOULD consider the signature to be in error.
             * An evaluator may "recognize" a subpacket, but not implement it.  The
             * purpose of the critical bit is to allow the signer to tell an
             * evaluator that it would prefer a new, unknown feature to generate an
             * error than be ignored.
             */
            /* non-critical subpackets: */
            hashedPacketsGen.setPreferredSymmetricAlgorithms(false,
                    PgpConstants.getAsArray(PgpConstants.sPreferredSymmetricAlgorithms));
            hashedPacketsGen.setPreferredHashAlgorithms(false,
                    PgpConstants.getAsArray(PgpConstants.sPreferredHashAlgorithms));
            hashedPacketsGen.setPreferredCompressionAlgorithms(false,
                    PgpConstants.getAsArray(PgpConstants.sPreferredCompressionAlgorithms));
            hashedPacketsGen.setPrimaryUserID(false, primary);

            /* critical subpackets: we consider those important for a modern pgp implementation */
            hashedPacketsGen.setSignatureCreationTime(true, creationTime);
            // Request that senders add the MDC to the message (authenticate unsigned messages)
            hashedPacketsGen.setFeature(true, Features.FEATURE_MODIFICATION_DETECTION);
            hashedPacketsGen.setKeyFlags(true, flags);
            if (expiry > 0) {
                hashedPacketsGen.setKeyExpirationTime(
                        true, expiry - pKey.getCreationTime().getTime() / 1000);
            }
        }

        return hashedPacketsGen;
    }

    private static PGPSignature generateUserIdSignature(
            PGPSignatureGenerator sGen, Date creationTime,
            PGPPrivateKey masterPrivateKey, PGPPublicKey pKey, String userId, boolean primary,
            int flags, long expiry)
            throws IOException, PGPException, SignatureException {

        PGPSignatureSubpacketGenerator hashedPacketsGen =
                generateHashedSelfSigSubpackets(creationTime, pKey, primary, flags, expiry);
        sGen.setHashedSubpackets(hashedPacketsGen.generate());
        sGen.init(PGPSignature.POSITIVE_CERTIFICATION, masterPrivateKey);
        return sGen.generateCertification(userId, pKey);
    }

    private static PGPSignature generateUserAttributeSignature(
            PGPSignatureGenerator sGen, Date creationTime,
            PGPPrivateKey masterPrivateKey, PGPPublicKey pKey,
            PGPUserAttributeSubpacketVector vector,
            int flags, long expiry)
                throws IOException, PGPException, SignatureException {

        PGPSignatureSubpacketGenerator hashedPacketsGen =
                generateHashedSelfSigSubpackets(creationTime, pKey, false, flags, expiry);
        sGen.setHashedSubpackets(hashedPacketsGen.generate());
        sGen.init(PGPSignature.POSITIVE_CERTIFICATION, masterPrivateKey);
        return sGen.generateCertification(vector, pKey);
    }

    private static PGPSignature generateRevocationSignature(
            PGPSignatureGenerator sGen, Date creationTime,
            PGPPrivateKey masterPrivateKey, PGPPublicKey pKey, String userId)

        throws IOException, PGPException, SignatureException {
        PGPSignatureSubpacketGenerator subHashedPacketsGen = new PGPSignatureSubpacketGenerator();
        subHashedPacketsGen.setSignatureCreationTime(true, creationTime);
        sGen.setHashedSubpackets(subHashedPacketsGen.generate());
        sGen.init(PGPSignature.CERTIFICATION_REVOCATION, masterPrivateKey);
        return sGen.generateCertification(userId, pKey);
    }

    private static PGPSignature generateRevocationSignature(
            PGPSignatureGenerator sGen, Date creationTime,
            PGPPublicKey masterPublicKey, PGPPrivateKey masterPrivateKey, PGPPublicKey pKey)
            throws IOException, PGPException, SignatureException {

        PGPSignatureSubpacketGenerator subHashedPacketsGen = new PGPSignatureSubpacketGenerator();
        subHashedPacketsGen.setSignatureCreationTime(true, creationTime);
        sGen.setHashedSubpackets(subHashedPacketsGen.generate());
        // Generate key revocation or subkey revocation, depending on master/subkey-ness
        if (masterPublicKey.getKeyID() == pKey.getKeyID()) {
            sGen.init(PGPSignature.KEY_REVOCATION, masterPrivateKey);
            return sGen.generateCertification(masterPublicKey);
        } else {
            sGen.init(PGPSignature.SUBKEY_REVOCATION, masterPrivateKey);
            return sGen.generateCertification(masterPublicKey, pKey);
        }
    }

    static PGPSignature generateSubkeyBindingSignature(
            PGPSignatureGenerator sGen, Date creationTime,
            PGPPublicKey masterPublicKey, PGPPrivateKey masterPrivateKey,
            PGPPrivateKey subPrivateKey, PGPPublicKey pKey, int flags, long expiry)
            throws IOException, PGPException, SignatureException {

        PGPSignatureSubpacketGenerator unhashedPacketsGen = new PGPSignatureSubpacketGenerator();

        // If this key can sign, we need a primary key binding signature
        if ((flags & KeyFlags.SIGN_DATA) > 0) {
            // cross-certify signing keys
            PGPSignatureSubpacketGenerator subHashedPacketsGen = new PGPSignatureSubpacketGenerator();
            subHashedPacketsGen.setSignatureCreationTime(false, creationTime);
            PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                    pKey.getAlgorithm(), PgpConstants.SECRET_KEY_SIGNATURE_HASH_ALGO)
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
            PGPSignatureGenerator subSigGen = new PGPSignatureGenerator(signerBuilder);
            subSigGen.init(PGPSignature.PRIMARYKEY_BINDING, subPrivateKey);
            subSigGen.setHashedSubpackets(subHashedPacketsGen.generate());
            PGPSignature certification = subSigGen.generateCertification(masterPublicKey, pKey);
            unhashedPacketsGen.setEmbeddedSignature(true, certification);
        }

        PGPSignatureSubpacketGenerator hashedPacketsGen;
        {
            hashedPacketsGen = new PGPSignatureSubpacketGenerator();
            hashedPacketsGen.setSignatureCreationTime(true, creationTime);
            hashedPacketsGen.setKeyFlags(true, flags);
            if (expiry > 0) {
                hashedPacketsGen.setKeyExpirationTime(true,
                        expiry - pKey.getCreationTime().getTime() / 1000);
            }
        }

        sGen.init(PGPSignature.SUBKEY_BINDING, masterPrivateKey);
        sGen.setHashedSubpackets(hashedPacketsGen.generate());
        sGen.setUnhashedSubpackets(unhashedPacketsGen.generate());

        return sGen.generateCertification(masterPublicKey, pKey);

    }

    /** Returns all flags valid for this key.
     *
     * This method does not do any validity checks on the signature, so it should not be used on
     * a non-canonicalized key!
     *
     */
    private static int readKeyFlags(PGPPublicKey key) {
        int flags = 0;
        //noinspection unchecked
        for(PGPSignature sig : new IterableIterator<PGPSignature>(key.getSignatures())) {
            if (sig.getHashedSubPackets() == null) {
                continue;
            }
            flags |= sig.getHashedSubPackets().getKeyFlags();
        }
        return flags;
    }

    private static boolean isDummy(PGPSecretKey secretKey) {
        S2K s2k = secretKey.getS2K();
        return s2k != null && s2k.getType() == S2K.GNU_DUMMY_S2K
                && s2k.getProtectionMode() != S2K.GNU_PROTECTION_MODE_DIVERT_TO_CARD;
    }

    private static boolean isDivertToCard(PGPSecretKey secretKey) {
        S2K s2k = secretKey.getS2K();
        return s2k != null && s2k.getType() == S2K.GNU_DUMMY_S2K
                && s2k.getProtectionMode() == S2K.GNU_PROTECTION_MODE_DIVERT_TO_CARD;
    }

    private static boolean checkSmartCardCompatibility(PGPSecretKey key, OperationLog log, int indent) {
        PGPPublicKey publicKey = key.getPublicKey();
        int algorithm = publicKey.getAlgorithm();
        if (algorithm != PublicKeyAlgorithmTags.RSA_ENCRYPT &&
                algorithm != PublicKeyAlgorithmTags.RSA_SIGN &&
                algorithm != PublicKeyAlgorithmTags.RSA_GENERAL) {
            log.add(LogType.MSG_MF_ERROR_BAD_NFC_ALGO, indent + 1);
            return false;
        }

        // Key size must be 2048
        int keySize = publicKey.getBitStrength();
        if (keySize != 2048) {
            log.add(LogType.MSG_MF_ERROR_BAD_NFC_SIZE, indent + 1);
            return false;
        }

        // Secret key parts must be available
        if (isDivertToCard(key) || isDummy(key)) {
            log.add(LogType.MSG_MF_ERROR_BAD_NFC_STRIPPED, indent + 1);
            return false;
        }

        return true;
    }
}
