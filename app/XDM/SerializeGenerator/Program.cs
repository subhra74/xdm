//using System;
//using System.Reflection;
//using System.Text;
//using XDM.Core.Lib.Common;

//namespace SerializeGenerator
//{
//    class Program
//    {
//        static int c = 1;
//        static void Main(string[] args)
//        {
//            var type = typeof(Config);

//        }

//        private static void GenerateCode(Type type, StringBuilder serializer, StringBuilder deserializer)
//        {
//            foreach (var property in type.GetProperties(BindingFlags.Instance | BindingFlags.Public | BindingFlags.DeclaredOnly))
//            {
//                if (property.PropertyType.IsArray || property.PropertyType.GetInterface("IEnumerable") != null)
//                {
//                    if (property.PropertyType.IsArray)
//                    {
//                        serializer.Append($"var c{c++}=obj.{property.Name}?.Length??0;\r\n");
//                        serializer.Append($"writer.Write(c{c});\r\n");
//                        serializer.Append($"if(obj.{property.Name}!=null)" + "{foreach(var item in obj." + property.Name + "){");
//                        //deserializer.Append($"var c{c++}=reader.ReadInt32();\r\n");
//                        //deserializer.Append("if(c" + c + ">0){");

//                        GenerateCode(property.PropertyType, serializer, deserializer);
//                        serializer.Append("}}\r\n");

//                    }
//                }

//                if (property.PropertyType == typeof(string)
//                    || property.PropertyType == typeof(int)
//                    || property.PropertyType == typeof(long)
//                    || property.PropertyType == typeof(bool)
//                    || property.PropertyType == typeof(double)
//                    || property.PropertyType == typeof(float)
//                    || property.PropertyType.IsEnum)
//                {
//                    if (property.PropertyType == typeof(string))
//                    {
//                        serializer.Append($"writer.Write(obj.{property.Name}??string.Empty);\r\n");
//                        deserializer.Append($"obj.{property.Name}=Helper.ReadString(reader);\r\n");
//                    }
//                    else if (property.PropertyType.IsEnum)
//                    {
//                        serializer.Append($"writer.Write((int)obj.{property.Name});\r\n");
//                        deserializer.Append($"obj.{property.Name}=({property.PropertyType})reader.ReadInt32();\r\n");
//                    }
//                    else if (property.PropertyType == typeof(int))
//                    {
//                        serializer.Append($"writer.Write(obj.{property.Name});\r\n");
//                        deserializer.Append($"obj.{property.Name}=reader.ReadInt32();\r\n");
//                    }
//                    else if (property.PropertyType == typeof(long))
//                    {
//                        serializer.Append($"writer.Write(obj.{property.Name});\r\n");
//                        deserializer.Append($"obj.{property.Name}=reader.ReadInt64();\r\n");
//                    }
//                    else if (property.PropertyType == typeof(bool))
//                    {
//                        serializer.Append($"writer.Write(obj.{property.Name});\r\n");
//                        deserializer.Append($"obj.{property.Name}=reader.ReadBoolean();\r\n");
//                    }
//                    else if (property.PropertyType == typeof(double))
//                    {
//                        serializer.Append($"writer.Write(obj.{property.Name});\r\n");
//                        deserializer.Append($"obj.{property.Name}=reader.ReadDouble();\r\n");
//                    }
//                    else if (property.PropertyType == typeof(float))
//                    {
//                        serializer.Append($"writer.Write(obj.{property.Name});\r\n");
//                        deserializer.Append($"obj.{property.Name}=reader.ReadFloat();\r\n");
//                    }
//                }
//                else
//                {
//                    if (property.PropertyType.IsValueType || property.PropertyType.IsClass)
//                    {
//                        GenerateCode(property.PropertyType, serializer, deserializer);
//                    }
//                }
//            }
//        }
//    }
//}
