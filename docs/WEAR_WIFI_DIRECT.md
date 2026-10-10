# Matriz Wear OS: consulta directa por Wi-Fi sin actualizar el teléfono

Esta rama añade un modo de consulta independiente para el reloj. La app Wear OS consulta por HTTPS un endpoint de Google Apps Script; no envía solicitudes al servicio Wearable del teléfono. Por eso, la versión instalada de Matriz en el teléfono no necesita actualizarse.

## Requisitos antes de probar

1. Tener acceso de editor al proyecto de Google Apps Script que actualmente publica la URL configurada en `WearCloudRepository.kt`.
2. Tener permiso para leer el Google Sheet usado por Matriz.
3. Instalar la nueva APK del reloj después de que el build de esta rama termine.
4. Hacer una actualización del despliegue de Apps Script. Esto no actualiza ni reinstala la app del teléfono.

## Configuración de seguridad del backend

**No reutilices `MatrizFCM` como token del reloj.** El endpoint nuevo usa una propiedad de script separada.

1. Abre el proyecto Apps Script asociado al despliegue existente.
2. Reemplaza el contenido de `Code.gs` con la versión de este repositorio: `firebase-apps-script/Code.gs`.
3. En **Configuración del proyecto → Propiedades del script**, crea la propiedad `WEAR_API_TOKEN` con un valor aleatorio propio de al menos 32 caracteres. No publiques ese valor ni lo agregues al repositorio.
4. En **Implementar → Gestionar implementaciones**, edita la implementación de aplicación web existente y selecciona **Nueva versión**. Conserva la URL de implementación que utiliza Matriz. La aplicación web debe poder recibir solicitudes HTTPS sin iniciar sesión interactiva; el token separado valida las consultas de Wear OS. El script se ejecuta como la cuenta propietaria y solo devuelve datos de lectura.
5. Instala la APK nueva en el reloj. Abre Matriz Wear, pulsa **Configurar acceso**, introduce el mismo token y pulsa **Guardar token**. Luego pulsa **Sincronizar**.

El token se envía en el cuerpo de una petición HTTPS POST, no en la URL. Se guarda en las preferencias privadas de la aplicación del reloj.

## Datos entregados

- **Matriz:** todas las filas de la hoja `Matriz `; se muestran en lista desplazable y con búsqueda.
- **Solicitud:** todas las filas de `Solicitud`, con búsqueda.
- **Filtro Fecha:** todas las filas de `Filtro Fecha`; si esa hoja está vacía, usa las filas de Matriz cuya fecha corresponda al día actual.
- **Control:** todas las filas de `GraficaSuma`.
- **Semana 6:** todas las filas de la hoja `Cont-Sem-N` de la semana ISO actual; si no existe, usa la semana numerada más reciente.

Cada sección permite buscar por texto dentro de los registros cargados. Los registros leídos desde Sheets reflejan lo que ya esté sincronizado allí. Los cambios que solo existan en Room del teléfono y no hayan llegado a Sheets no pueden aparecer en el reloj con este método.

## Limitación de despliegue

El cambio de código en GitHub no actualiza automáticamente el proyecto Apps Script de Google. Hay que copiar el archivo al proyecto existente y publicar una nueva versión una vez. Esto es independiente de la APK del teléfono; no se requiere actualizarla.
