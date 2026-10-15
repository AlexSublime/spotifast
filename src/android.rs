//! Android platform bridges for system media controls and document picking.

use std::cell::Cell;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use fastframe_now_playing::{App, Command, Playback, State};
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong};
use jni::{JNIEnv, JavaVM};
use tokio::sync::{mpsc, oneshot};

type CoverResult = Result<Option<PathBuf>, String>;
type CoverSender = oneshot::Sender<CoverResult>;

struct MediaBridge {
    commands: mpsc::UnboundedSender<Command>,
    wake: Arc<dyn Fn() + Send + Sync>,
}

static MEDIA_BRIDGE: OnceLock<Mutex<Option<MediaBridge>>> = OnceLock::new();
static COVER_PICKERS: OnceLock<Mutex<HashMap<u64, CoverSender>>> = OnceLock::new();

fn media_bridge() -> &'static Mutex<Option<MediaBridge>> {
    MEDIA_BRIDGE.get_or_init(|| Mutex::new(None))
}

fn cover_pickers() -> &'static Mutex<HashMap<u64, CoverSender>> {
    COVER_PICKERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn with_env<T>(f: impl FnOnce(&mut JNIEnv<'_>) -> jni::errors::Result<T>) -> Result<T, String> {
    let context = ndk_context::android_context();
    // SAFETY: android-activity initializes this process-wide VM before android_main.
    let vm = unsafe { JavaVM::from_raw(context.vm().cast()) }.map_err(|e| e.to_string())?;
    let mut env = vm.attach_current_thread().map_err(|e| e.to_string())?;
    f(&mut env).map_err(|e| e.to_string())
}

// Native worker threads use Android's system classloader in FindClass. Resolve
// app classes through the running Application's loader instead.
fn app_class<'local>(env: &mut JNIEnv<'local>, name: &str) -> jni::errors::Result<JClass<'local>> {
    let application = env
        .call_static_method(
            "android/app/ActivityThread",
            "currentApplication",
            "()Landroid/app/Application;",
            &[],
        )?
        .l()?;
    let loader = env
        .call_method(
            &application,
            "getClassLoader",
            "()Ljava/lang/ClassLoader;",
            &[],
        )?
        .l()?;
    let name = env.new_string(name.replace('/', "."))?;
    let class = env
        .call_method(
            loader,
            "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&name)],
        )?
        .l()?;
    Ok(JClass::from(class))
}

/// Open a browser URL through Android's package resolver.
pub fn open_url(url: &str) -> Result<(), String> {
    with_env(|env| {
        let url = env.new_string(url)?;
        let class = app_class(env, "rocks.spotifast.SpotifastActivity")?;
        env.call_static_method(
            class,
            "openUrl",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&url)],
        )?;
        Ok(())
    })
}

/// Native picker result future, keyed by the UI request already in flight.
pub fn choose_cover(request: u64) -> std::pin::Pin<Box<dyn Future<Output = CoverResult> + Send>> {
    Box::pin(async move {
        let (sender, receiver) = oneshot::channel();
        cover_pickers()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(request, sender);

        let started = with_env(|env| {
            let class = app_class(env, "rocks.spotifast.SpotifastActivity")?;
            env.call_static_method(
                class,
                "chooseCover",
                "(J)Z",
                &[JValue::Long(request as jlong)],
            )?
            .z()
        });

        let started = match started {
            Ok(started) => started,
            Err(error) => {
                cover_pickers()
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .remove(&request);
                return Err(error.to_string());
            }
        };

        if !started {
            cover_pickers()
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .remove(&request);
            return Err("Spotifast activity is not available.".into());
        }

        receiver
            .await
            .unwrap_or_else(|_| Err("Android image picker was closed.".into()))
    })
}

/// Android's media session adapter. Commands are drained by the normal app
/// frame, then follow Spotifast's existing action path.
pub struct NowPlaying {
    commands: Mutex<mpsc::UnboundedReceiver<Command>>,
    last: Option<State>,
    last_local: bool,
    last_sent: Cell<Instant>,
}

impl NowPlaying {
    pub fn start(_app: App, wake: impl Fn() + Send + Sync + 'static) -> Self {
        let (sender, commands) = mpsc::unbounded_channel();
        *media_bridge().lock().unwrap_or_else(|p| p.into_inner()) = Some(MediaBridge {
            commands: sender,
            wake: Arc::new(wake),
        });
        Self {
            commands: Mutex::new(commands),
            last: None,
            last_local: false,
            last_sent: Cell::new(Instant::now() - Duration::from_secs(2)),
        }
    }

    pub fn commands(&self) -> Vec<Command> {
        let mut commands = Vec::new();
        let mut receiver = self.commands.lock().unwrap_or_else(|p| p.into_inner());
        while let Ok(command) = receiver.try_recv() {
            commands.push(command);
        }
        commands
    }

    pub fn seeked(&self, _position: Duration) {
        self.last_sent.set(Instant::now() - Duration::from_secs(2));
    }

    pub fn update(&mut self, state: State, local: bool) {
        let structural_change = self.last.as_ref().is_none_or(|last| {
            let mut old = last.clone();
            let mut new = state.clone();
            old.position = Duration::ZERO;
            new.position = Duration::ZERO;
            old != new
        }) || self.last_local != local;
        let changed = self.last.as_ref() != Some(&state) || self.last_local != local;
        if !changed
            || (!structural_change
                && state.playback == Playback::Playing
                && self.last_sent.get().elapsed() < Duration::from_secs(1))
        {
            return;
        }

        if let Err(error) = publish(&state, local) {
            log::warn!("Android media session update failed: {error}");
        }
        self.last = Some(state);
        self.last_local = local;
        self.last_sent.set(Instant::now());
    }
}

impl Drop for NowPlaying {
    fn drop(&mut self) {
        *media_bridge().lock().unwrap_or_else(|p| p.into_inner()) = None;
    }
}

fn publish(state: &State, local: bool) -> Result<(), String> {
    let track = state.track.as_ref();
    let playing = local && state.playback == Playback::Playing;
    let title = track.map_or_else(|| "".to_owned(), |track| track.title.clone());
    let artists = track.map_or_else(String::new, |track| track.artists.join(", "));
    let album = track.map_or_else(String::new, |track| track.album.clone());
    let art = track
        .and_then(|track| track.art_file.as_ref())
        .map(|path| path.to_string_lossy().into_owned());
    let position = state.position.as_millis().min(i64::MAX as u128) as jlong;
    let duration = track
        .and_then(|track| track.duration)
        .map_or(0, |value| value.as_millis().min(i64::MAX as u128) as jlong);

    with_env(|env| {
        let title = env.new_string(title)?;
        let artists = env.new_string(artists)?;
        let album = env.new_string(album)?;
        let art = art
            .map(|path| env.new_string(path))
            .transpose()?
            .map(JObject::from);
        let no_art = JObject::null();
        let art = art.as_ref().unwrap_or(&no_art);
        let class = app_class(env, "rocks.spotifast.PlaybackService")?;
        env.call_static_method(
            class,
            "publish",
            "(ZZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V",
            &[
                JValue::Bool(u8::from(local && track.is_some())),
                JValue::Bool(u8::from(playing)),
                JValue::Object(&title),
                JValue::Object(&artists),
                JValue::Object(&album),
                JValue::Object(art),
                JValue::Long(position),
                JValue::Long(duration),
            ],
        )?;
        Ok(())
    })
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_rocks_spotifast_PlaybackService_nativeMediaCommand(
    _env: JNIEnv,
    _class: JClass,
    action: jint,
    value: jlong,
) {
    let command = match action {
        1 => Command::Play,
        2 => Command::Pause,
        3 => Command::PlayPause,
        4 => Command::Next,
        5 => Command::Previous,
        6 => Command::SeekBy(value),
        _ => return,
    };
    let bridge = media_bridge()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .as_ref()
        .map(|bridge| (bridge.commands.clone(), Arc::clone(&bridge.wake)));
    if let Some((commands, wake)) = bridge {
        let _ = commands.send(command);
        wake();
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_rocks_spotifast_SpotifastActivity_nativeCoverPicked(
    mut env: JNIEnv,
    _class: JClass,
    request: jlong,
    path: JString,
    error: JString,
) {
    let result = if !error.is_null() {
        env.get_string(&error)
            .map(|value| Err(value.into()))
            .unwrap_or_else(|e| Err(e.to_string()))
    } else if path.is_null() {
        Ok(None)
    } else {
        env.get_string(&path)
            .map(|value| Ok(Some(PathBuf::from(String::from(value)))))
            .unwrap_or_else(|e| Err(e.to_string()))
    };
    if let Some(sender) = cover_pickers()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .remove(&(request as u64))
    {
        let _ = sender.send(result);
    }
}
