package ar.edu.utn.dds.k3003;


import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ar.edu.utn.dds.k3003.messaging.ReadModelEventPublisher;

import ar.edu.utn.dds.k3003.catedra.dtos.donaciones.EstadoDonacionEnum;
import ar.edu.utn.dds.k3003.catedra.dtos.donaciones.ProductoDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.incentivos.CategoriaDonadorEnum;
import ar.edu.utn.dds.k3003.catedra.dtos.incentivos.InsigniaDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.incentivos.MisionDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.incentivos.TipoMisionEnum;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaDonaciones;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaDonadoresYEntidades;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaIncentivos;
import ar.edu.utn.dds.k3003.model.DonacionSimulada;
import ar.edu.utn.dds.k3003.model.DonadorIncentivo;
import ar.edu.utn.dds.k3003.model.Insignia;
import ar.edu.utn.dds.k3003.model.Mision;
import ar.edu.utn.dds.k3003.model.MisionFactory;
import ar.edu.utn.dds.k3003.model.ProgresoMision;
import ar.edu.utn.dds.k3003.repositories.IncentivosMapper;
import ar.edu.utn.dds.k3003.repositories.DonadorIncentivoRepository;
import ar.edu.utn.dds.k3003.repositories.InsigniaRepository;
import ar.edu.utn.dds.k3003.repositories.MisionRepository;
import ar.edu.utn.dds.k3003.repositories.ProgresoMisionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Fachada implements FachadaIncentivos {
    private FachadaDonaciones fachadaDonaciones;
    private FachadaDonadoresYEntidades fachadaDonadoresYEntidades;
    private final DonadorIncentivoRepository donadorRepository;
    private final InsigniaRepository insigniaRepository;
    private final MisionRepository misionRepository;
    private final MeterRegistry meterRegistry;
    private final ProgresoMisionRepository progresoMisionRepository;

    @Autowired(required = false)
    private ReadModelEventPublisher readModelEventPublisher;

   private int contadorIds=1;

    private String generarId(){
      return java.util.UUID.randomUUID().toString();
    }

  private void incrementarMetrica(String nombre) {
    if (meterRegistry != null) {
        Counter.builder(nombre)
                .register(meterRegistry)
                .increment();
    }
}

  @Autowired
  public Fachada(DonadorIncentivoRepository donadorRepository,
        InsigniaRepository insigniaRepository,
        MisionRepository misionRepository, 
        MeterRegistry meterRegistry,
        ProgresoMisionRepository progresoMisionRepository) {
    /*
    Para que se ejecuten correctamente los tests, se necesita tener un constructor vacio
    Es decir, que no reciba parametros.
    Si necesitan un constructor con parametros
    Java permite tener varios constructores conviviendo sin conflictos.
    */
    this.donadorRepository = donadorRepository;
    this.insigniaRepository = insigniaRepository;
    this.misionRepository = misionRepository;
    this.meterRegistry = meterRegistry;
    this.progresoMisionRepository = progresoMisionRepository;
  }

   @Override
    public void setFachadaDonadoresYEntidades(FachadaDonadoresYEntidades fachada) {
        this.fachadaDonadoresYEntidades = fachada;
    }

    @Override
    public void setFachadaDonaciones(FachadaDonaciones f) { 
      this.fachadaDonaciones = f; 
    }

    private void validarQueDonadorExiste(String donadorID) {
      if(donadorID==null){ 
        incrementarMetrica("donatrack.incentivos.errores");
        throw new IllegalArgumentException("El donador no existe en el sistema");
      }
        fachadaDonadoresYEntidades.buscarDonadorPorID(donadorID);
    }

    private DonadorIncentivo obtenerODarDeAltaDonador(String donadorID) {
        DonadorIncentivo donador = donadorRepository.findById(donadorID).orElse(null);

        if (donador == null) {
            donador = new DonadorIncentivo(donadorID);
            donadorRepository.save(donador);
        }

        return donador;
    }

    private ProgresoMision obtenerProgresoActual(DonadorIncentivo donador) {
    for (Mision mision : donador.getMisiones()) {

        ProgresoMision progreso =
                progresoMisionRepository
                        .findByDonadorIdAndMisionId(
                                donador.getDonadorId(),
                                mision.getId()
                        )
                        .orElse(null);

        if (progreso != null && !progreso.estaCompletada()) {
            return progreso;
        }
    }
    return null;
    }

    private List<ProgresoMision> obtenerProgresosDelDonador(
        DonadorIncentivo donador) {

    return progresoMisionRepository
            .findByDonadorId(donador.getDonadorId());
}
    


  @Override
  public InsigniaDTO agregarInsignia(InsigniaDTO insigniaDTO){
    if(insigniaDTO==null){
      incrementarMetrica("donatrack.incentivos.errores");
      throw new IllegalArgumentException("Insignia requerida");
    }

    if(insigniaDTO.nombre() == null || insigniaDTO.nombre().isBlank()) throw new IllegalArgumentException("Nombre requerido");
    if(insigniaDTO.id() != null && insigniaRepository.findById(insigniaDTO.id()).orElse(null) != null){
      incrementarMetrica("donatrack.incentivos.errores");
      throw new IllegalArgumentException("La insignia ya existe");
    }
    String id = insigniaDTO.id() != null ? insigniaDTO.id() : generarId();
    Insignia insignia = new Insignia(id, insigniaDTO.nombre(), insigniaDTO.descripcion());
    insigniaRepository.save(insignia); 
    incrementarMetrica("donatrack.incentivos.insignias.creadas");
    publicarEvento("insignia", insignia.getId(), "UPSERT", snapshotInsignia(insignia));
    return IncentivosMapper.toInsigniaDTO(insignia);
  }

  @Override 
  public MisionDTO agregarMision(MisionDTO misionDTO){
    if(misionDTO == null){
      incrementarMetrica("donatrack.incentivos.errores");
      throw new IllegalArgumentException("La mision no existe");
    }

    if(misionDTO.id() != null && misionRepository.findById(misionDTO.id()).orElse(null) != null){
      incrementarMetrica("donatrack.incentivos.errores");
      throw new IllegalArgumentException("La mision ya existe");
    }

    if (misionDTO.nombre() == null || misionDTO.nombre().isBlank() || misionDTO.tipo() == null
        || misionDTO.categoriaInicio() == null || misionDTO.categoriaFin() == null
        || misionDTO.insigniaID() == null || misionDTO.insigniaID().isBlank())
      throw new IllegalArgumentException("Datos de misión incompletos");
    insigniaRepository.findById(misionDTO.insigniaID()).orElseThrow(() -> new NoSuchElementException("Insignia inexistente"));
    String id= generarId();

    Mision mision = MisionFactory.crear(id, misionDTO); 
    
    misionRepository.save(mision);
    incrementarMetrica("donatrack.incentivos.misiones.creadas");
    publicarEvento("mision", mision.getId(), "UPSERT", snapshotMision(mision));
    return IncentivosMapper.toMisionDTO(mision);
  }

  @Override
  public void asignarInsigniaADonador(String donadorID, InsigniaDTO dto){
    this.validarQueDonadorExiste(donadorID);
    if(dto == null || dto.id() == null){
      incrementarMetrica("donatrack.incentivos.errores");
        throw new IllegalArgumentException("InsigniaDTO invalida");
    }

    DonadorIncentivo donador = obtenerODarDeAltaDonador(donadorID);

    Insignia insignia = insigniaRepository.findById(dto.id()).orElse(null);
    if(insignia == null){
      incrementarMetrica("donatrack.incentivos.errores");
        throw new NoSuchElementException("La insignia no existe");
    }

    donador.agregarInsignia(insignia);
    donadorRepository.save(donador);
    publicarEvento("incentivo_donador", donadorID, "UPSERT", snapshotIncentivosDonador(donador));
  }

  @Override
  public void asignarMisionADonador(String donadorID, MisionDTO misionDTO){
    this.validarQueDonadorExiste(donadorID);
    
    if(misionDTO == null || misionDTO.id() == null){
      incrementarMetrica("donatrack.incentivos.errores");
        throw new IllegalArgumentException("MisionDTO invalida");
    }

    DonadorIncentivo donador= obtenerODarDeAltaDonador(donadorID);

    Mision mision = misionRepository.findById(misionDTO.id()).orElse(null);
    if(mision == null){
      incrementarMetrica("donatrack.incentivos.errores");
      throw new NoSuchElementException("la misión no existe");
    }

    if (progresoMisionRepository.existsByDonadorIdAndMisionId(
        donadorID,
        mision.getId())) {

    incrementarMetrica("donatrack.incentivos.errores");

    throw new RuntimeException(
            "La misión ya está asignada al donador"
    );
}

    if (obtenerProgresoActual(donador) != null) throw new IllegalArgumentException("El donador ya tiene una misión en curso");
    if (mision.getCategoriaInicio() != donador.getCategoria()) throw new IllegalArgumentException("La misión no corresponde a la categoría del donador");
    donador.asignarMision(mision);

    if (!progresoMisionRepository.existsByDonadorIdAndMisionId(
        donadorID,
        mision.getId())) {

    ProgresoMision progreso =
            new ProgresoMision(
                    donadorID,
                    mision.getId()
            );

    progresoMisionRepository.save(progreso);
  }
    donadorRepository.save(donador);
    publicarEvento("incentivo_donador", donadorID, "UPSERT", snapshotIncentivosDonador(donador));
    incrementarMetrica("donatrack.incentivos.misiones.asignadas");
  }

  @Override
  public List<InsigniaDTO> getInsigniasDeDonador(String donadorID){
    incrementarMetrica("donatrack.incentivos.consultas");

    DonadorIncentivo donador= donadorRepository.findById(donadorID).orElse(null);

    if(donador==null){
      incrementarMetrica("donatrack.incentivos.errores");
      validarQueDonadorExiste(donadorID);
      return List.of();
    }
    return donador.getInsignias().stream()
                  .map(insignia -> IncentivosMapper.toInsigniaDTO(insignia))
                  .toList();
  }

  @Override
  public MisionDTO getMisionEnCursoDeDonador(String donadorID){
    incrementarMetrica("donatrack.incentivos.consultas");

    DonadorIncentivo donador = donadorRepository.findById(donadorID).orElse(null);

    if(donador == null){
      incrementarMetrica("donatrack.incentivos.errores");
      validarQueDonadorExiste(donadorID);
      return null;
    }
  ProgresoMision progreso = obtenerProgresoActual(donador);

  if (progreso == null) {
    incrementarMetrica("donatrack.incentivos.errores");
      return null;
  }

  Mision mision = misionRepository
        .findById(progreso.getMisionId())
        .orElseThrow(() ->
                new NoSuchElementException("Misión inexistente"));

  return IncentivosMapper.toMisionDTO(mision);
}

  @Override
  @Transactional
  public void procesarDonador(String donadorID) {
    this.validarQueDonadorExiste(donadorID);

    DonadorIncentivo donador = obtenerODarDeAltaDonador(donadorID);

    var donacionesDTO =
            fachadaDonaciones.buscarPorDonadorYFechaInicio(
                    donadorID,
                    LocalDate.of(2025, 1, 1)
            );

    List<DonacionSimulada> donaciones =
            donacionesDTO.stream()
                    .map(d -> {
                        ProductoDTO producto =
                                fachadaDonaciones.buscarProductoPorID(
                                        d.productoID()
                                );

                        return new DonacionSimulada(
                                producto.categoriaID(),
                                d.cantidad(),
                                d.estado() == EstadoDonacionEnum.ACEPTADA
                        );
                    })
                    .toList();

    List<ProgresoMision> progresos =
            obtenerProgresosDelDonador(donador);

    for (ProgresoMision progreso : progresos) {

        Mision mision =
                misionRepository
                        .findById(progreso.getMisionId())
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Misión inexistente"
                                )
                        );


if (mision.estaCompleta(donaciones)  && !progreso.estaCompletada()) {

    // La misión se completa por primera vez
    

        Insignia insignia =
                insigniaRepository
                        .findById(mision.getInsigniaID())
                        .orElse(null);

        if (insignia != null) {
            donador.agregarInsignia(insignia);
        }

        CategoriaDonadorEnum nuevaCategoria =
                mision.getCategoriaFin();

        if (nuevaCategoria != null) {
            donador.avanzarCategoria(nuevaCategoria);
            fachadaDonadoresYEntidades.modifcarCategoria(donadorID, nuevaCategoria.name());
        }

        progreso.completar();
        progresoMisionRepository.save(progreso);
        incrementarMetrica("donatrack.incentivos.misiones.completadas");
        ar.edu.utn.dds.k3003.observability.DomainEvents.info(org.slf4j.LoggerFactory.getLogger(Fachada.class), "mision.completada donador={} mision={} categoria={}", donadorID, mision.getId(), donador.getCategoria());

} else if ( !mision.estaCompleta(donaciones) &&
        progreso.estaCompletada()
        && mision.getTipo() == TipoMisionEnum.DONACIONES_EXITOSAS
) {

    // Donaciones Exitosas dejó de cumplirse.
    // Se pierde todo el progreso de la misión.

    progreso.descompletar();

    // Volver a la categoría desde la cual se obtenía la misión
    CategoriaDonadorEnum categoriaAnterior =
            mision.getCategoriaInicio();

    if (categoriaAnterior != null) {
        donador.retrocederCategoria(categoriaAnterior);
        fachadaDonadoresYEntidades.modifcarCategoria(donadorID, categoriaAnterior.name());
    }

    // Quitar la insignia correspondiente a la misión
    if (mision.getInsigniaID() != null) {
        donador.quitarInsignia(
                mision.getInsigniaID()
        );
    }

    progresoMisionRepository.save(progreso);
    incrementarMetrica("donatrack.incentivos.misiones.revocadas");
    ar.edu.utn.dds.k3003.observability.DomainEvents.info(org.slf4j.LoggerFactory.getLogger(Fachada.class), "mision.revocada donador={} mision={} categoria={}", donadorID, mision.getId(), donador.getCategoria());
}

}
    donadorRepository.save(donador);
    publicarEvento("incentivo_donador", donadorID, "UPSERT", snapshotIncentivosDonador(donador));
    incrementarMetrica("donatrack.incentivos.donadores.procesados");
  }

   public List<InsigniaDTO> getInsignias() {
       incrementarMetrica("donatrack.incentivos.consultas");

        return insigniaRepository.findAll()
            .stream()
            .map(IncentivosMapper::toInsigniaDTO)
            .toList();
    }
    public InsigniaDTO buscarInsigniaPorID(String id) {
         incrementarMetrica("donatrack.incentivos.consultas");

         Insignia insignia = insigniaRepository.findById(id)
            .orElseThrow(() ->
                    new NoSuchElementException("Insignia inexistente"));

        return IncentivosMapper.toInsigniaDTO(insignia);
    }

    public List<MisionDTO> getMisiones() {
        incrementarMetrica("donatrack.incentivos.consultas");
        return misionRepository.findAll()
            .stream()
            .map(IncentivosMapper::toMisionDTO)
            .toList();
    }

    public MisionDTO buscarMisionPorID(String id) {
       incrementarMetrica("donatrack.incentivos.consultas");

        Mision mision = misionRepository.findById(id)
            .orElseThrow(() ->
                    new NoSuchElementException("Misión inexistente"));

        return IncentivosMapper.toMisionDTO(mision);
    }

    @Transactional
    public void limpiarDatos() {
    donadorRepository.findAll().forEach(donador ->
        publicarEvento("incentivo_donador", donador.getDonadorId(), "DELETE", Map.of()));
    misionRepository.findAll().forEach(mision ->
        publicarEvento("mision", mision.getId(), "DELETE", Map.of()));
    insigniaRepository.findAll().forEach(insignia ->
        publicarEvento("insignia", insignia.getId(), "DELETE", Map.of()));
    progresoMisionRepository.deleteAll();
    donadorRepository.deleteAll();
    misionRepository.deleteAll();
    insigniaRepository.deleteAll();
}

  @Transactional
  public InsigniaDTO modificarInsignia(String id, InsigniaDTO dto) {
    Insignia insignia = insigniaRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Insignia inexistente"));
    insignia.modificarDatos(dto.nombre(), dto.descripcion());
    Insignia guardada = insigniaRepository.save(insignia);
    publicarEvento("insignia", guardada.getId(), "UPSERT", snapshotInsignia(guardada));
    return IncentivosMapper.toInsigniaDTO(guardada);
  }
  @Transactional
  public void eliminarInsignia(String id) {
    buscarInsigniaPorID(id);
    if (misionRepository.findAll().stream().anyMatch(m -> id.equals(m.getInsigniaID()))
        || donadorRepository.findAll().stream().anyMatch(d -> d.getInsignias().stream().anyMatch(b -> id.equals(b.getId()))))
      throw new IllegalArgumentException("La insignia está en uso por una misión o donador");
    insigniaRepository.deleteById(id);
    publicarEvento("insignia", id, "DELETE", Map.of());
  }
  @Transactional
  public MisionDTO modificarMision(String id, MisionDTO dto) {
    Mision mision = misionRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Misión inexistente"));
    if (mision.getTipo() != dto.tipo() || mision.getCategoriaInicio() != dto.categoriaInicio()
        || mision.getCategoriaFin() != dto.categoriaFin() || !java.util.Objects.equals(mision.getInsigniaID(), dto.insigniaID()))
      throw new IllegalArgumentException("Para cambiar reglas o recompensa, cree otra misión");
    mision.modificarNombre(dto.nombre());
    Mision guardada = misionRepository.save(mision);
    publicarEvento("mision", guardada.getId(), "UPSERT", snapshotMision(guardada));
    return IncentivosMapper.toMisionDTO(guardada);
  }
  @Transactional
  public void eliminarMision(String id) {
    buscarMisionPorID(id);
    if (donadorRepository.findAll().stream().anyMatch(d -> d.getMisiones().stream().anyMatch(m -> id.equals(m.getId()))))
      throw new IllegalArgumentException("La misión está asignada a un donador");
    misionRepository.deleteById(id);
    publicarEvento("mision", id, "DELETE", Map.of());
  }

  private void publicarEvento(
      String tipo, String id, String operacion, Map<String, Object> payload) {
    if (readModelEventPublisher != null) {
      readModelEventPublisher.publicar(tipo, id, operacion, payload);
    }
  }

  private Map<String, Object> snapshotInsignia(Insignia insignia) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", insignia.getId());
    snapshot.put("nombre", insignia.getNombre());
    snapshot.put("descripcion", insignia.getDescripcion());
    return snapshot;
  }

  private Map<String, Object> snapshotMision(Mision mision) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", mision.getId());
    snapshot.put("nombre", mision.getNombre());
    snapshot.put("insigniaID", mision.getInsigniaID());
    snapshot.put("categoriaInicio", mision.getCategoriaInicio() == null ? null : mision.getCategoriaInicio().name());
    snapshot.put("categoriaFin", mision.getCategoriaFin() == null ? null : mision.getCategoriaFin().name());
    snapshot.put("tipo", mision.getTipo() == null ? null : mision.getTipo().name());
    return snapshot;
  }

  private Map<String, Object> snapshotIncentivosDonador(DonadorIncentivo donador) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("donadorID", donador.getDonadorId());
    snapshot.put("categoria", donador.getCategoria() == null ? null : donador.getCategoria().name());
    snapshot.put("insignias", donador.getInsignias().stream().map(this::snapshotInsignia).toList());
    snapshot.put("misiones", donador.getMisiones().stream().map(this::snapshotMision).toList());
    snapshot.put("progresos", progresoMisionRepository.findByDonadorId(donador.getDonadorId()).stream()
        .map(progreso -> {
          Map<String, Object> dato = new LinkedHashMap<>();
          dato.put("misionID", progreso.getMisionId());
          dato.put("completada", progreso.estaCompletada());
          return dato;
        }).toList());
    return snapshot;
  }

}
