package matlabcontrol.extensions;

/*
 * Copyright (c) 2011, Joshua Kaplan
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *  - Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 *  - Neither the name of matlabcontrol nor the names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import matlabcontrol.MatlabInvocationException;
import matlabcontrol.MatlabProxy;
import matlabcontrol.MatlabProxy.MatlabThreadProxy;
import matlabcontrol.extensions.MatlabReturns.MatlabReturnN;
import matlabcontrol.extensions.MatlabType.MatlabTypeSerializedGetter;

/**
 *
 * @since 4.1.0
 * 
 * @author <a href="mailto:nonother@gmail.com">Joshua Kaplan</a>
 */
public class MatlabFunctionLinker
{
    
    public static final class MatlabVariable extends MatlabType
    {
        private final String _name;
        
        public MatlabVariable(String name)
        {
            //Validate variable name
            
            if(name.isEmpty())
            {
                throw new IllegalArgumentException("Invalid MATLAB variable name: " + name);
            }
            
            char[] nameChars = name.toCharArray();
            
            if(!Character.isLetter(nameChars[0]))
            {
                throw new IllegalArgumentException("Invalid MATLAB variable name: " + name);
            }
            
            for(char element : nameChars)
            {
                if(!(Character.isLetter(element) || Character.isDigit(element) || element == '_'))
                {
                    throw new IllegalArgumentException("Invalid MATLAB variable name: " + name);
                }
            }
            
            _name = name;
        }
        
        String getName()
        {
            return _name;
        }

        @Override
        MatlabTypeSerializedSetter getSerializedSetter()
        {
            return new MatlabVariableSerializedSetter(_name);
        }
        
        private static class MatlabVariableSerializedSetter implements MatlabTypeSerializedSetter
        {
            private final String _name;
            
            private MatlabVariableSerializedSetter(String name)
            {
                _name = name;
            }

            @Override
            public void setInMatlab(MatlabThreadProxy proxy, String variableName) throws MatlabInvocationException
            {
                proxy.eval(variableName + " = " + _name + ";");
            }
        }
    }
    
    private MatlabFunctionLinker() { }
    
    /**************************************************************************************************************\
    |*                                        Linking & Validation                                                *|
    \**************************************************************************************************************/
    
    public static <T> T link(Class<T> functionInterface, MatlabProxy matlabProxy)
    {
        if(!functionInterface.isInterface())
        {
            throw new LinkingException(functionInterface.getName() + " is not an interface");
        }
        
        //Information about the functions
        Map<Method, ResolvedFunctionInfo> functionsInfo = new ConcurrentHashMap<Method, ResolvedFunctionInfo>();
        
        //Validate and retrieve information about all of the methods in the interface
        for(Method method : functionInterface.getMethods())
        {
            MatlabFunctionInfo annotation = method.getAnnotation(MatlabFunctionInfo.class);
            
            //Check that all invariants are held
            checkMethodAnnotation(method, annotation);
            checkMethodReturn(method, annotation);
            checkMethodExceptions(method);
            
            functionsInfo.put(method, resolveMatlabFunctionInfo(functionInterface, method, annotation));
        }
        
        T functionProxy = (T) Proxy.newProxyInstance(functionInterface.getClassLoader(),
                new Class<?>[] { functionInterface }, new MatlabFunctionInvocationHandler(matlabProxy, functionsInfo));
        
        return functionProxy;
    }
    
    private static ResolvedFunctionInfo resolveMatlabFunctionInfo(Class<?> functionInterface, Method method,
            MatlabFunctionInfo annotation)
    {
        String functionName;
        String containingDirectory;

        //If the name was specified, meaning the function is expected to be on MATLAB's path
        if(!annotation.name().isEmpty())
        {
            functionName = annotation.name();
            containingDirectory = null;
        }
        else
        {
            String path; //Only need for exception messages
            File mFile;
            
            if(!annotation.absolutePath().isEmpty())
            {
                path = annotation.absolutePath();
                
                mFile = new File(annotation.absolutePath());
            }
            else
            {
                path = annotation.relativePath();
                
                File interfaceLocation = getClassLocation(functionInterface);
                try
                {   
                    //If this line succeeds then, then the interface is inside of a jar, so the m-file is as well
                    JarFile jar = new JarFile(interfaceLocation);

                    try
                    {
                        JarEntry entry = jar.getJarEntry(annotation.relativePath());

                        if(entry == null)
                        {
                             throw new LinkingException("Unable to find m-file inside of jar\n" +
                                "method: " + method.getName() + "\n" +
                                "path: " + annotation.relativePath() + "\n" +
                                "jar location: " + interfaceLocation.getAbsolutePath());
                        }

                        String entryName = entry.getName();
                        if(!entryName.endsWith(".m"))
                        {
                            throw new LinkingException("Specified m-file does not end in .m\n" +
                                    "method: " + method.getName() + "\n" +
                                    "path: " + annotation.relativePath() + "\n" +
                                    "jar location: " + interfaceLocation.getAbsolutePath());
                        }

                        functionName = entryName.substring(entryName.lastIndexOf("/") + 1, entryName.length() - 2);
                        mFile = extractFromJar(jar, entry, functionName);
                        jar.close();
                    }
                    catch(IOException e)
                    {
                        throw new LinkingException("Unable to extract m-file from jar\n" +
                                "method: " + method.getName() + "\n" +
                                "path: " + annotation.relativePath() + "\n" +
                                "jar location: " + interfaceLocation, e);
                    }
                }
                //Interface is not located inside a jar, so neither is the m-file
                catch(IOException e)
                {
                    mFile = new File(interfaceLocation, annotation.relativePath());
                }
            }
            
            //Resolve canonical path
            try
            {
                mFile = mFile.getCanonicalFile();
            }
            catch(IOException ex)
            {
                throw new LinkingException("Unable to resolve canonical path of specified function\n" +
                        "method: " + method.getName() + "\n" +
                        "path:" + path + "\n" +
                        "non-canonical path: " + mFile.getAbsolutePath(), ex);
            }
            
            //Validate file location
            if(!mFile.exists())
            {
                throw new LinkingException("Specified m-file does not exist\n" + 
                        "method: " + method.getName() + "\n" +
                        "path: " + path + "\n" +
                        "resolved as: " + mFile.getAbsolutePath());
            }
            if(!mFile.isFile())
            {
                throw new LinkingException("Specified m-file is not a file\n" + 
                        "method: " + method.getName() + "\n" +
                        "path: " + path + "\n" +
                        "resolved as: " + mFile.getAbsolutePath());
            }
            if(!mFile.getName().endsWith(".m"))
            {
                throw new LinkingException("Specified m-file does not end in .m\n" + 
                        "method: " + method.getName() + "\n" +
                        "path: " + path + "\n" +
                        "resolved as: " + mFile.getAbsolutePath());
            }
            
            //Parse out the name of the function and the directory containing it
            containingDirectory = mFile.getParent();
            functionName = mFile.getName().substring(0, mFile.getName().length() - 2); 
        }
        
        ResolvedFunctionInfo info = new ResolvedFunctionInfo(functionName, containingDirectory,
                annotation.nargout(), getReturnTypes(method, annotation), method.getParameterTypes());

        return info;
    }
    
    private static File extractFromJar(JarFile jar, JarEntry entry, String functionName) throws IOException
    {
        //Source
        InputStream entryStream = jar.getInputStream(entry);

        //Destination
        File tempDir = new File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString());
        File destFile = new File(tempDir, functionName + ".m");
        if(destFile.exists())
        {
            throw new IOException("Unable to extract m-file, randomly generated path already defined\n" +
                    "function: " + functionName + "\n" +
                    "generated path: " + destFile.getAbsolutePath());
        }
        destFile.getParentFile().mkdirs();
        destFile.deleteOnExit();

        //Copy source to destination
        final int BUFFER_SIZE = 2048;
        byte[] buffer = new byte[BUFFER_SIZE];
        BufferedOutputStream dest = new BufferedOutputStream(new FileOutputStream(destFile), BUFFER_SIZE);
        int count;
        while((count = entryStream.read(buffer, 0, BUFFER_SIZE)) != -1)
        {
           dest.write(buffer, 0, count);
        }
        dest.flush();
        dest.close();

        return destFile;
    }
    
    /**
     * A simple holder of information which closely matches {@link MatlabFunctionInfo}. However, it further resolves
     * the information provider by users of {@code MatlabFunctionInfo}.
     */
    private static class ResolvedFunctionInfo implements Serializable
    {
        /**
         * The name of the function.
         */
        final String name;
        
        /**
         * Number of return arguments.
         */
        final int nargout;
        
        /**
         * The types of each returned argument. The length of this array will always match nargout.
         */
        final Class<?>[] returnTypes;
        
        /**
         * The directory containing the function. Will be {@code null} if the function has been specified as being on
         * MATLAB's path.
         */
        final String containingDirectory;
        
        /**
         * If the method is making uses of classes as either a return value or parameters which are to be specially
         * handled so that they interact with MATLAB differently.
         */
        final boolean usesMatlabTypes;
        
        /**
         * The declared types of the arguments of the method associated with the function.
         */
        final Class<?>[] parameterTypes;
        
        private ResolvedFunctionInfo(String name, String containingDirectory, int nargout, Class<?>[] returnTypes,
                Class<?>[] parameterTypes)
        {
            this.name = name;
            this.nargout = nargout;
            this.containingDirectory = containingDirectory;
            this.returnTypes = returnTypes;
            this.parameterTypes = parameterTypes;
            
            this.usesMatlabTypes = usesMatlabTypes(returnTypes, parameterTypes);
        }
        
        private static boolean usesMatlabTypes(Class<?>[] returnTypes, Class<?>[] parameterTypes)
        {
            boolean usesMatlabTypes = false;

            List<Class<?>> types = new ArrayList<Class<?>>();
            types.addAll(Arrays.asList(returnTypes));
            types.addAll(Arrays.asList(parameterTypes));

            for(Class<?> type : types)
            {
                if(MatlabType.class.isAssignableFrom(type))
                {
                    usesMatlabTypes = true;
                    break;
                }
            }

            return usesMatlabTypes;
        }
    }
    
    private static void checkMethodAnnotation(Method method, MatlabFunctionInfo annotation)
    {
        if(annotation == null)
        {
            throw new LinkingException(method + " does not have a " + MatlabFunctionInfo.class.getName() +
                    " annotation.");
        }
        
        //Verify exactly one of name, absolutePath, and relativePath has been specified
        boolean hasName = !annotation.name().isEmpty();
        boolean hasAbsolutePath = !annotation.absolutePath().isEmpty();
        boolean hasRelativePath = !annotation.relativePath().isEmpty();
        if( (hasName && (hasAbsolutePath || hasRelativePath)) ||
            (hasAbsolutePath && (hasName || hasRelativePath)) ||
            (hasRelativePath && (hasAbsolutePath || hasName)) ||
            (!hasName && !hasAbsolutePath && !hasRelativePath) )
        {
            throw new LinkingException(method + "'s " + MatlabFunctionInfo.class.getName() + " annotation must " +
                    "specify either a function name, an absolute path, or a relative path. It must specify exactly " +
                    "one.");
        }
    }
    
    private static void checkMethodReturn(Method method, MatlabFunctionInfo annotation)
    {
        //Returned arguments must be 0 or greater
        if(annotation.nargout() < 0)
        {
            throw new LinkingException(method + "'s " + MatlabFunctionInfo.class.getName() + 
                    " annotation specifies a negative nargout of " + annotation.nargout() + ". nargout must be " +
                    " 0 or greater.");
        }

        //The return type of the method
        Class<?> methodReturn = method.getReturnType();
        
        //MatlabVariable is a special class that can never be a return type
        if(methodReturn.equals(MatlabVariable.class))
        {
            throw new LinkingException(method + " cannot have a return type of " + MatlabVariable.class.getName());
        }
        
        //If void return type then nargout must be 0
        if(methodReturn.equals(Void.TYPE) && annotation.nargout() != 0)
        {
            throw new LinkingException(method + " has a void return type but has a non-zero nargout value: " +
                    annotation.nargout());
        }

        //If a return type is specified then nargout must be greater than 0
        if(!methodReturn.equals(Void.TYPE) && annotation.nargout() == 0)
        {
            throw new LinkingException(method + " has a non-void return type but does not " +
                    "specify the number of return arguments or specified 0.");
        }
        
        //The types of the returns, does not need to be specified unless the return type is a subclass of MatlabReturnN
        Class<?>[] annotatedReturns = annotation.returnTypes();
        
        //If return types were specified
        if(annotatedReturns.length != 0)
        {
            if(!(MatlabReturnN.class.isAssignableFrom(methodReturn) || Object[].class.equals(methodReturn)))
            {
                throw new LinkingException(method + " has annotated return types but provides an incompatible method " +
                        "return type. The method's return type must either be Object[] or a MatlabReturnN class " + 
                        "where N is an integer.");
            }
            
            if(annotatedReturns.length != annotation.nargout())
            {
                throw new LinkingException(method + " has a differing amount of return arguments specified by " +
                        "nargout and the number of annotated return types.\n" +
                        "nargout: " + annotation.nargout() + "\n" +
                        "number of annotated return types: " + annotatedReturns.length);
            }
            
            for(Class<?> type : annotatedReturns)
            {
                if(type.isPrimitive())
                {
                    throw new LinkingException(method + " has annotated a primitive return type. Primitive types are " +
                            "not supported");
                }
            }
        }
        
        //If using a MatlabReturnN subclass as a return type
        if(MatlabReturnN.class.isAssignableFrom(methodReturn))
        {
            int numReturns = MatlabReturns.getNumberOfReturns((Class<? extends MatlabReturnN>) methodReturn);
            
            if(annotatedReturns.length != numReturns)
            {
                throw new LinkingException(method + " has a return type for " + numReturns + " return arguments, " +
                        "but has " + annotatedReturns.length + " return types annotated");
            }
            
            if(numReturns != annotation.nargout())
            {
                throw new LinkingException(method + " has a return type for " + numReturns  + " return arguments, " +
                        "but specifies an nargout of " + annotation.nargout());
            }
        }
        //Otherwise if multiple return values, the return type must be an array
        else if(annotation.nargout() > 1 && !methodReturn.isArray())
        {
            throw new LinkingException(method + " must have a return type of an array or MatlabReturnN where N is an " +
                    "integer");
        }
    }
    
    //Only to be called after checkMethodReturn(...) has been called
    private static Class<?>[] getReturnTypes(Method method, MatlabFunctionInfo annotation)
    {
        Class<?>[] returnTypes;
        
        Class<?> methodReturn = method.getReturnType();
        Class<?>[] annotatedReturns = annotation.returnTypes();
        
        //For 0 or 1 return types, the return type is the method return type
        if(annotation.nargout() == 0 || annotation.nargout() == 1)
        {
            returnTypes = new Class<?>[] { methodReturn };
        }
        //If no annotated return types then the method return type is an array and each return type is the component type
        else if(annotatedReturns.length == 0)
        {
            Class<?> componentType = methodReturn.getComponentType();
            returnTypes = new Class<?>[annotation.nargout()];
            for(int i = 0; i < returnTypes.length; i++)
            {
                returnTypes[i] = componentType;
            }
        }
        //Otherwise the return types are the annotated returns
        else
        {
            returnTypes = annotatedReturns;
        }
        
        return returnTypes;
    }
    
    private static void checkMethodExceptions(Method method)
    {
        //Check the method throws MatlabInvocationException
        if(!Arrays.asList(method.getExceptionTypes()).contains(MatlabInvocationException.class))
        {
            throw new LinkingException(method.getName() + " must throw " + MatlabInvocationException.class);
        }
    }
    
    private static File getClassLocation(Class<?> clazz)
    {
        try
        {
            URL url = clazz.getProtectionDomain().getCodeSource().getLocation();
            File file = new File(url.toURI().getPath()).getCanonicalFile();
            
            return file;
        }
        catch(IOException e)
        {
            throw new LinkingException("Unable to determine location of " + clazz.getName(), e);
        }
        catch(URISyntaxException e)
        {
            throw new LinkingException("Unable to determine location of " + clazz.getName(), e);
        }
    }
    
    /**
     * Represents an issue linking a Java method to a MATLAB function.
     * 
     * @since 4.1.0
     * @author <a href="mailto:nonother@gmail.com">Joshua Kaplan</a>
     */
    public static class LinkingException extends RuntimeException
    {
        private LinkingException(String msg)
        {
            super(msg);
        }
        
        private LinkingException(String msg, Throwable cause)
        {
            super(msg, cause);
        }
    }
    
    
    /**************************************************************************************************************\
    |*                                        Function Invocation                                                 *|
    \**************************************************************************************************************/
    

    private static class MatlabFunctionInvocationHandler implements InvocationHandler
    {
        private final MatlabProxy _proxy;
        private final Map<Method, ResolvedFunctionInfo> _functionsInfo;
        
        private MatlabFunctionInvocationHandler(MatlabProxy proxy, Map<Method, ResolvedFunctionInfo> functionsInfo)
        {
            _proxy = proxy;
            _functionsInfo = functionsInfo;
        }

        @Override
        public Object invoke(Object o, Method method, Object[] args) throws MatlabInvocationException
        {   
            ResolvedFunctionInfo functionInfo = _functionsInfo.get(method);
            
            //Invoke the function
            Object[] functionResult;
            if(functionInfo.usesMatlabTypes)
            {
                //Replace all MatlabTypes with their serialized setters
                for(int i = 0; i < args.length; i++)
                {
                    if(args[i] instanceof MatlabType)
                    {
                        MatlabType matlabType = (MatlabType) args[i];
                        MatlabType.MatlabTypeSerializedSetter setter = matlabType.getSerializedSetter();
                        args[i] = setter;
                    }
                }
                
                functionResult = _proxy.invokeAndWait(new CustomFunctionInvocation(functionInfo, args));
                
                //For each returned value that was serialized getter, deserialize it
                Object[] transformedResult = new Object[functionResult.length];
                for(int i = 0; i < functionResult.length; i++)
                {
                    Object result = functionResult[i];
                    if(result instanceof MatlabTypeSerializedGetter)
                    {
                        result = ((MatlabType.MatlabTypeSerializedGetter) result).deserialize();
                    }
                    transformedResult[i] = result;
                }
                functionResult = transformedResult;
            }
            else
            {
                functionResult = _proxy.invokeAndWait(new StandardFunctionInvocation(functionInfo, args));
            }
            
            //Process the result
            Object result = convertReturnType(functionResult, functionInfo.returnTypes, method.getReturnType()); 
            
            return result;
        }
        
        private Object convertReturnType(Object[] result, Class<?>[] returnTypes, Class<?> methodReturn)
        {
            Object toReturn;
            
            if(result.length == 0)
            {
                toReturn = result;
            }
            else if(result.length == 1)
            {
                toReturn = convertToType(result[0], returnTypes[0]);
            }
            else
            {
                Object newValuesArray;
                if(methodReturn.isArray())
                {
                    newValuesArray = Array.newInstance(methodReturn.getComponentType(), result.length);
                }
                else
                {
                    newValuesArray = new Object[result.length];
                }
                
                for(int i = 0; i < result.length; i++)
                {
                    if(result[i] != null)
                    {
                        Array.set(newValuesArray, i, convertToType(result[i], returnTypes[i]));
                    }
                }
                
                if(MatlabReturnN.class.isAssignableFrom(methodReturn))
                {
                    toReturn = MatlabReturns.createMatlabReturn((Object[]) newValuesArray);
                }
                else
                {
                    toReturn = newValuesArray;
                }
            }
            
            return toReturn;
        }
        
        private Object convertToType(Object value, Class<?> returnType)
        {
            Object toReturn;
            if(value == null)
            {
                toReturn = null;
            }
            else if(returnType.isPrimitive())
            {
                toReturn = convertPrimitiveReturnType(value, returnType);
            }
            else
            {
                if(!returnType.isAssignableFrom(value.getClass()))
                {
                    throw new IncompatibleReturnException("Required return type is incompatible with the type " +
                            "actually returned\n" +
                            "Required type: " + returnType.getCanonicalName() + "\n" +
                            "Returned type: " + value.getClass().getCanonicalName());
                }
                
                toReturn = value;
            }
            
            return toReturn;
        }

        private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_AUTOBOXED = new ConcurrentHashMap<Class<?>, Class<?>>();
        static
        {
            PRIMITIVE_TO_AUTOBOXED.put(byte.class, Byte.class);
            PRIMITIVE_TO_AUTOBOXED.put(short.class, Short.class);
            PRIMITIVE_TO_AUTOBOXED.put(int.class, Integer.class);
            PRIMITIVE_TO_AUTOBOXED.put(long.class, Long.class);
            PRIMITIVE_TO_AUTOBOXED.put(double.class, Double.class);
            PRIMITIVE_TO_AUTOBOXED.put(float.class, Float.class);
            PRIMITIVE_TO_AUTOBOXED.put(boolean.class, Boolean.class);
            PRIMITIVE_TO_AUTOBOXED.put(char.class, Character.class);
        }
        
        private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_ARRAY_OF = new ConcurrentHashMap<Class<?>, Class<?>>();
        static
        {
            PRIMITIVE_TO_ARRAY_OF.put(byte.class, byte[].class);
            PRIMITIVE_TO_ARRAY_OF.put(short.class, short[].class);
            PRIMITIVE_TO_ARRAY_OF.put(int.class, int[].class);
            PRIMITIVE_TO_ARRAY_OF.put(long.class, long[].class);
            PRIMITIVE_TO_ARRAY_OF.put(double.class, double[].class);
            PRIMITIVE_TO_ARRAY_OF.put(float.class, float[].class);
            PRIMITIVE_TO_ARRAY_OF.put(boolean.class, boolean[].class);
            PRIMITIVE_TO_ARRAY_OF.put(char.class, char[].class);
        }
        
        private Object convertPrimitiveReturnType(Object value, Class<?> returnType)
        {
            Class<?> actualType = value.getClass();
            
            Class<?> autoBoxOfReturnType = PRIMITIVE_TO_AUTOBOXED.get(returnType);
            Class<?> arrayOfReturnType = PRIMITIVE_TO_ARRAY_OF.get(returnType);
            
            Object result;
            if(actualType.equals(autoBoxOfReturnType))
            {
                result = value;
            }
            else if(actualType.equals(arrayOfReturnType))
            {
                if(Array.getLength(value) != 1)
                {
                    throw new IncompatibleReturnException("Array of " + returnType.getCanonicalName() + " does not " +
                                "have exactly 1 value.");
                }
                
                result = Array.get(value, 0);
            }
            else
            {
                throw new IncompatibleReturnException("Required return type is incompatible with the type actually " +
                        "returned\n" +
                        "Required type: " + returnType.getCanonicalName() + "\n" +
                        "Returned type: " + actualType.getCanonicalName());
            }
            
            return result;
        }
    }
    
    private static class CustomFunctionInvocation implements MatlabProxy.MatlabThreadCallable<Object[]>, Serializable
    {
        private final ResolvedFunctionInfo _functionInfo;
        private final Object[] _args;
        
        private CustomFunctionInvocation(ResolvedFunctionInfo functionInfo, Object[] args)
        {
            _functionInfo = functionInfo;
            _args = args;
        }

        @Override
        public Object[] call(MatlabThreadProxy proxy) throws MatlabInvocationException
        {
            String initialDir = null;
            
            //If the function was specified as not being on MATLAB's path
            if(_functionInfo.containingDirectory != null)
            {
                //Initial directory before cding
                initialDir = (String) proxy.returningFeval("pwd", 1)[0];
                
                //No need to change directory
                if(initialDir.equals(_functionInfo.containingDirectory))
                {
                    initialDir = null;
                }
                //Change directory to where the function is located
                else
                {
                    proxy.feval("cd", _functionInfo.containingDirectory);
                }
            }
            
            String[] parameterNames = new String[0];
            String[] returnNames = new String[0];
            try
            {
                //Set all arguments as MATLAB variables and build a function call using those variables
                String functionStr = _functionInfo.name + "(";
                parameterNames = generateNames(proxy, "args_", _args.length);
                for(int i = 0; i < _args.length; i++)
                {
                    Object arg = _args[i];
                    String name = parameterNames[i];
                    
                    if(arg instanceof MatlabType.MatlabTypeSerializedSetter)
                    {
                        ((MatlabType.MatlabTypeSerializedSetter) arg).setInMatlab(proxy, name);
                    }
                    else
                    {
                        proxy.setVariable(name, arg);
                    }
                    
                    functionStr += name;
                    if(i != _args.length - 1)
                    {
                        functionStr += ", ";
                    }
                }
                functionStr += ");";
                
                //Return arguments
                if(_functionInfo.nargout != 0)
                {
                    returnNames = generateNames(proxy, "return_", _functionInfo.nargout);
                    String returnStr = "[";
                    for(int i = 0; i < returnNames.length; i++)
                    {
                        returnStr += returnNames[i];
                        
                        if(i != returnNames.length - 1)
                        {
                            returnStr += ", ";
                        }
                    }
                    returnStr += "]";
                    
                    functionStr = returnStr + " = " + functionStr;
                }
                
                System.out.println(functionStr); //Testing
                
                //Invoke the function
                proxy.eval(functionStr);
                
                //Get the results
                Object[] results = new Object[_functionInfo.nargout];
                
                for(int i = 0; i < results.length; i++)
                {
                    Class<?> returnType = _functionInfo.returnTypes[i];
                    String returnName = returnNames[i];
                    
                    if(MatlabType.class.isAssignableFrom(returnType))
                    {
                        MatlabTypeSerializedGetter getter =
                                MatlabType.createSerializedGetter((Class<? extends MatlabType>) returnType);
                        getter.getInMatlab(proxy, returnName);
                        results[i] = getter;
                    }
                    else
                    {
                        results[i] = proxy.getVariable(returnName);
                    }
                }
                /*
                if(MatlabType.class.isAssignableFrom(_functionInfo.returnType) ||
                   (_functionInfo.returnType.isArray() &&
                    MatlabType.class.isAssignableFrom(_functionInfo.returnType.getComponentType())))
                {
                    Class<? extends MatlabType> matlabTypeClass;
                    if(_functionInfo.returnType.isArray())
                    {
                        matlabTypeClass = (Class<? extends MatlabType>) _functionInfo.returnType.getComponentType();
                    }
                    else
                    {
                        matlabTypeClass = (Class<? extends MatlabType>) _functionInfo.returnType;
                    }
                    
                    MatlabTypeSerializedGetter[] getters = new MatlabTypeSerializedGetter[_functionInfo.nargout];
                    for(int i = 0; i < _functionInfo.nargout; i++)
                    {
                        getters[i] = MatlabType.createSerializedGetter(matlabTypeClass);
                        getters[i].getInMatlab(proxy, returnNames[i]);
                    }
                    results = getters;
                }
                else if(_functionInfo.nargout != 0)
                {
                    results = new Object[_functionInfo.nargout];
                    for(int i = 0; i < _functionInfo.nargout; i++)
                    {
                        results[i] = proxy.getVariable(returnNames[i]);
                    }
                }
                else
                {
                    results = new Object[0];
                }
                 * 
                 */
                
                return results;
            }
            //Restore MATLAB's state to what it was before the function call happened
            finally
            {
                try
                {
                    //Clear all variables used
                    List<String> createdVariables = new ArrayList<String>();
                    createdVariables.addAll(Arrays.asList(parameterNames));
                    createdVariables.addAll(Arrays.asList(returnNames));
                    String variablesStr = "";
                    for(int i = 0; i < createdVariables.size(); i++)
                    {
                        variablesStr += createdVariables.get(i);

                        if(i != createdVariables.size() - 1)
                        {
                            variablesStr += " ";
                        }
                    }
                    proxy.eval("clear " + variablesStr);
                }
                finally
                {
                    //If necessary, change back to the directory MATLAB was in before the function was invoked
                    if(initialDir != null)
                    {
                        proxy.feval("cd", initialDir);
                    }
                }
            }
        }
        
        private String[] generateNames(MatlabThreadProxy proxy, String root, int amount)
                throws MatlabInvocationException
        {
            //Build set of currently taken names
            Set<String> takenNames = new HashSet<String>(Arrays.asList((String[]) proxy.returningEval("who", 1)[0]));
            
            //Generate names
            List<String> generatedNames = new ArrayList<String>();
            int genSequenence = 0;
            while(generatedNames.size() != amount)
            {
                String generatedName = root + genSequenence;
                while(takenNames.contains(generatedName))
                {
                    genSequenence++;
                    generatedName = root + genSequenence;
                }
                genSequenence++;
                generatedNames.add(generatedName);
            }
            
            return generatedNames.toArray(new String[generatedNames.size()]);
        }
    }
    
    private static class StandardFunctionInvocation implements MatlabProxy.MatlabThreadCallable<Object[]>, Serializable
    {
        private final ResolvedFunctionInfo _functionInfo;
        private final Object[] _args;
        
        private StandardFunctionInvocation(ResolvedFunctionInfo functionInfo, Object[] args)
        {
            _functionInfo = functionInfo;
            _args = args;
        }
        
        @Override
        public Object[] call(MatlabThreadProxy proxy) throws MatlabInvocationException
        {
            String initialDir = null;
            
            //If the function was specified as not being on MATLAB's path
            if(_functionInfo.containingDirectory != null)
            {
                //Initial directory before cding
                initialDir = (String) proxy.returningFeval("pwd", 1)[0];
                
                //No need to change directory
                if(initialDir.equals(_functionInfo.containingDirectory))
                {
                    initialDir = null;
                }
                //Change directory to where the function is located
                else
                {
                    proxy.feval("cd", _functionInfo.containingDirectory);
                }
            }
            
            //Invoke function
            try
            {
                Object[] result;
                
                if(_functionInfo.nargout == 0)
                {
                    proxy.feval(_functionInfo.name, _args);
                    result = null;
                }
                else
                {
                    result = proxy.returningFeval(_functionInfo.name, _functionInfo.nargout, _args);
                }
            
                return result;
            }
            //If necessary, change back to the directory MATLAB was in before the function was invoked
            finally
            {
                if(initialDir != null)
                {
                    proxy.feval("cd", initialDir);
                }
            }
        }
    }
}