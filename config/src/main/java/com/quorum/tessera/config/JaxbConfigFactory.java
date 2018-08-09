package com.quorum.tessera.config;

import com.quorum.tessera.config.util.JaxbUtil;
import com.quorum.tessera.config.keys.KeyGenerator;
import com.quorum.tessera.config.keys.KeyGeneratorFactory;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validation;
import javax.validation.Validator;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JaxbConfigFactory implements ConfigFactory {


    private final KeyGenerator generator = KeyGeneratorFactory.newFactory().create();
    
    @Override
    public Config create(final InputStream configData, final ArgonOptions keygenConfig, final String... filenames) {

        final List<KeyData> newKeys = Stream
            .of(filenames)
            .map(name -> generator.generate(name, keygenConfig))
            .map(kd -> new KeyData(
                    new KeyDataConfig(
                        new PrivateKeyData(
                            kd.getConfig().getValue(),
                            kd.getConfig().getSnonce(),
                            kd.getConfig().getAsalt(),
                            kd.getConfig().getSbox(),
                            kd.getConfig().getArgonOptions(),
                            null
                        ),
                        kd.getConfig().getType()
                    ),
                    kd.getPrivateKey(),
                    kd.getPublicKey(),
                    kd.getPrivateKeyPath(),
                    kd.getPublicKeyPath()
                )
            )
            .collect(Collectors.toList());



        final Config config = JaxbUtil.unmarshal(configData, Config.class);

        final Validator validator = Validation.byDefaultProvider()
            .configure()
            .ignoreXmlConfiguration()
            .buildValidatorFactory()
            .getValidator();

        Set<ConstraintViolation<Config>> violations = validator.validate(config);

        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }

        config.getKeys().getKeyData().addAll(newKeys);

        return config;
    }

}
