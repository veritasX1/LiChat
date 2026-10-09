//! The core for Python (Ubuntu app, lichat-cli): `import lichat_core`. Bytes in, bytes out; errors as ValueError.

use pyo3::exceptions::PyValueError;
use pyo3::prelude::*;
use pyo3::types::PyBytes;

use crate::{Device as Core, Error, Received};

fn err(e: Error) -> PyErr {
    PyValueError::new_err(e.to_string())
}

fn key(bytes: &[u8]) -> PyResult<[u8; 32]> {
    bytes.try_into().map_err(|_| PyValueError::new_err("Schlüssel muss 32 Bytes haben"))
}

// Send: the app calls it from its worker and its window thread – never at the same time, every call holds the GIL.
#[pyclass]
struct Device {
    inner: Core,
}

#[pymethods]
impl Device {
    #[new]
    fn new(identity: &str) -> PyResult<Self> {
        Ok(Self { inner: Core::new(identity).map_err(err)? })
    }

    #[staticmethod]
    fn load(sealed: &[u8], key_bytes: &[u8]) -> PyResult<Self> {
        Ok(Self { inner: Core::load(sealed, &key(key_bytes)?).map_err(err)? })
    }

    fn save<'py>(&self, py: Python<'py>, key_bytes: &[u8]) -> PyResult<Bound<'py, PyBytes>> {
        Ok(PyBytes::new(py, &self.inner.save(&key(key_bytes)?).map_err(err)?))
    }

    #[getter]
    fn identity(&self) -> String {
        self.inner.identity().to_string()
    }

    fn identity_key<'py>(&self, py: Python<'py>) -> Bound<'py, PyBytes> {
        PyBytes::new(py, &self.inner.identity_key())
    }

    fn key_packages<'py>(&self, py: Python<'py>, count: usize) -> PyResult<Vec<Bound<'py, PyBytes>>> {
        Ok(self.inner.key_packages(count).map_err(err)?.iter().map(|k| PyBytes::new(py, k)).collect())
    }

    fn last_resort_key_package<'py>(&self, py: Python<'py>) -> PyResult<Bound<'py, PyBytes>> {
        Ok(PyBytes::new(py, &self.inner.last_resort_key_package().map_err(err)?))
    }

    /// -> (user name, public signature key) of a claimed key package, its signature checked.
    #[staticmethod]
    fn key_package_owner<'py>(py: Python<'py>, key_package: &[u8]) -> PyResult<(String, Bound<'py, PyBytes>)> {
        let (identity, key) = Core::key_package_owner(key_package).map_err(err)?;
        Ok((identity, PyBytes::new(py, &key)))
    }

    /// -> (conversation id, welcome)
    fn start_conversation<'py>(&mut self, py: Python<'py>, key_packages: Vec<Vec<u8>>) -> PyResult<(Bound<'py, PyBytes>, Bound<'py, PyBytes>)> {
        let started = self.inner.start_conversation(&key_packages).map_err(err)?;
        Ok((PyBytes::new(py, &started.conversation), PyBytes::new(py, &started.welcome)))
    }

    fn join<'py>(&mut self, py: Python<'py>, welcome: &[u8]) -> PyResult<Bound<'py, PyBytes>> {
        Ok(PyBytes::new(py, &self.inner.join(welcome).map_err(err)?))
    }

    fn encrypt<'py>(&mut self, py: Python<'py>, conversation: &[u8], payload: &[u8]) -> PyResult<Bound<'py, PyBytes>> {
        Ok(PyBytes::new(py, &self.inner.encrypt(conversation, payload).map_err(err)?))
    }

    fn update_keys<'py>(&mut self, py: Python<'py>, conversation: &[u8]) -> PyResult<Bound<'py, PyBytes>> {
        Ok(PyBytes::new(py, &self.inner.update_keys(conversation).map_err(err)?))
    }

    /// -> {"kind": "message", "conversation", "sender", "payload"} or {"kind": "changed", "conversation"}
    fn receive<'py>(&mut self, py: Python<'py>, data: &[u8]) -> PyResult<Bound<'py, pyo3::types::PyDict>> {
        let out = pyo3::types::PyDict::new(py);
        match self.inner.receive(data).map_err(err)? {
            Received::Message { conversation, sender, payload } => {
                out.set_item("kind", "message")?;
                out.set_item("conversation", PyBytes::new(py, &conversation))?;
                out.set_item("sender", sender)?;
                out.set_item("payload", PyBytes::new(py, &payload))?;
            }
            Received::Changed { conversation } => {
                out.set_item("kind", "changed")?;
                out.set_item("conversation", PyBytes::new(py, &conversation))?;
            }
        }
        Ok(out)
    }

    fn members(&self, conversation: &[u8]) -> PyResult<Vec<String>> {
        self.inner.members(conversation).map_err(err)
    }

    fn conversations<'py>(&self, py: Python<'py>) -> Vec<Bound<'py, PyBytes>> {
        self.inner.conversations().iter().map(|c| PyBytes::new(py, c)).collect()
    }

    fn safety_code<'py>(&self, py: Python<'py>, conversation: &[u8]) -> PyResult<Bound<'py, PyBytes>> {
        Ok(PyBytes::new(py, &self.inner.safety_code(conversation).map_err(err)?))
    }
}

#[pymodule]
fn lichat_core(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_class::<Device>()?;
    Ok(())
}
