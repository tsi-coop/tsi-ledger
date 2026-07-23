package org.tsicoop.ledger.framework;

import org.mindrot.jbcrypt.BCrypt;

/** BCrypt hashing for operator (console user) passwords — see Setup/Auth/Users. */
public class PasswordHasher {

    private static final int BCRYPT_LOG_ROUNDS = 12;

    public String hashPassword(String plaintextPassword) {
        return BCrypt.hashpw(plaintextPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
    }

    public boolean checkPassword(String plaintextPassword, String hashedPassword) {
        return BCrypt.checkpw(plaintextPassword, hashedPassword);
    }
}
