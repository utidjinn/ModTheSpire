package com.evacipated.cardcrawl.modthespire;

import javassist.ByteArrayClassPath;
import javassist.ClassPool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

// Custom ClassLoader
// When loading STS DesktopLauncher (main entry point), skips searching the parent classloader
// Parent classloader is us and will find our fake DesktopLauncher rather than the real game
// Also loads from an InputStream, in our case the corepatches.jar resource
// Otherwise acts like URLClassLoader
public class MTSClassLoader extends URLClassLoader
{
    private ClassLoader parent;
    private Map<String, byte[]> classes = new HashMap<>();
    private Map<String, Class<?>> definedClasses = new HashMap<>();
    private Map<String, byte[]> resources = new HashMap<>();

    public MTSClassLoader(InputStream stream, URL[] urls, ClassLoader parent) throws IOException
    {
        super(urls, null);

        this.parent = parent;
        JarInputStream is = new JarInputStream(stream);
        JarEntry entry = is.getNextJarEntry();
        while (entry != null) {
            if (entry.getName().contains(".class")) {
                String className = entry.getName().replace(".class", "").replace('/', '.');
                byte[] classBytes = bufferStream(is);
                classes.put(className, classBytes);
            } else if (!entry.isDirectory()) {
                byte[] bytes = bufferStream(is);
                resources.put(entry.getName(), bytes);
            }
            entry = is.getNextJarEntry();
        }
    }

    @Override
    public InputStream getResourceAsStream(String name)
    {
        if (resources.containsKey(name)) {
            return new ByteArrayInputStream(resources.get(name));
        }
        return super.getResourceAsStream(name);
    }

    private byte[] bufferStream(InputStream is) throws IOException
    {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        int nextValue = is.read();
        while (nextValue != -1) {
            byteStream.write(nextValue);
            nextValue = is.read();
        }
        return byteStream.toByteArray();
    }

    @Override
    public void addURL(URL url)
    {
        super.addURL(url);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        // Stop the log4j exploit by removing this class
        if (name.equals("org.apache.logging.log4j.core.lookup.JndiLookup")) {
            throw new ClassNotFoundException();
        }
        if (name.startsWith("com.codedisaster.steamworks") || name.startsWith("com.google.gson") || name.equals("com.megacrit.cardcrawl.desktop.DesktopLauncher")) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = findClass(name);
                if (c == null) {
                    c = super.loadClass(name);
                }
            }
            return c;
        } else {
            try {
                return parent.loadClass(name);
            } catch (ClassNotFoundException e) {
                return super.loadClass(name);
            }
        }
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException
    {
        Class<?> ret;
        try {
            ret = super.findClass(name);
        } catch (ClassNotFoundException e) {
            ret = definedClasses.get(name);
            if (ret == null) {
                byte[] classBytes = classes.remove(name);
                if (classBytes == null)
                    throw new ClassNotFoundException(name);
                ret = defineClass(name, classBytes, 0, classBytes.length, (ProtectionDomain) null);
                definedClasses.put(name, ret);
            }
        }
        return ret;
    }

    public void addStreamToClassPool(ClassPool pool)
    {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            pool.insertClassPath(new ByteArrayClassPath(entry.getKey(), entry.getValue()));
        }
    }
}
