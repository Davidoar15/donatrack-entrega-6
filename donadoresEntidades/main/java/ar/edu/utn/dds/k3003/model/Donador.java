package ar.edu.utn.dds.k3003.model;

import ar.edu.utn.dds.k3003.catedra.dtos.donadoresYEntidades.EstadoDonadorEnum;
import jakarta.persistence.*;
import java.util.Objects;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
public class Donador {

  @Id
  private String id;

  @Column
  private String nombre;

  @Column
  private String apellido;

  @Column
  private Integer edad;

  @Column
  private String email;

  @Column
  private String nroDocumento;

  @Column
  private String domicilio;

  @Enumerated(EnumType.STRING)
  @Column
  private EstadoDonadorEnum estado;

  @Column
  private String categoria;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "donador_historial_categorias",
      joinColumns = @JoinColumn(name = "donador_id"))
  @Column(name = "categoria", nullable = false)
  @OrderColumn(name = "orden")
  private List<String> historialCategorias = new ArrayList<>();


  @OneToMany(cascade = CascadeType.ALL)
  @JoinColumn(name = "donador_id")
  private List<Queja> listaDeQuejas = new ArrayList<>();

  @ElementCollection
  @Enumerated(EnumType.STRING)
  private List<EstadoDonadorEnum> historialEstados = new ArrayList<>();

  public Donador(String nombre, String apellido, Integer edad, String email, String nroDocumento, String domicilio) {
    this.nombre = nombre;
    this.apellido = apellido;
    this.edad = edad;
    this.email = email;
    this.nroDocumento = nroDocumento;
    this.domicilio = domicilio;
    this.estado = EstadoDonadorEnum.VERIFICADO;
    this.historialEstados.add(this.estado);
  }

  public Boolean puedeHacerDonacion() {
    if (this.estado == EstadoDonadorEnum.BANEADO) return false;
    if (this.estado == EstadoDonadorEnum.SOSPECHOSO) {
      return ThreadLocalRandom.current().nextBoolean();
    }
    return true;
  }
  public void cambiarEstado(EstadoDonadorEnum nuevoEstado) {
    if (nuevoEstado == null) throw new IllegalArgumentException("Estado requerido");
    if (this.estado != nuevoEstado) this.historialEstados.add(nuevoEstado);
    this.estado = nuevoEstado;
  }

  public void registrarQueja(Queja queja) {
    this.listaDeQuejas.add(queja);
    int cantidadQuejas = this.listaDeQuejas.size();

    if (cantidadQuejas >= 10) {
      cambiarEstado(EstadoDonadorEnum.BANEADO);
    } else if (cantidadQuejas >= 5) {
      cambiarEstado(EstadoDonadorEnum.SOSPECHOSO);
    }
  }
  /** Registra la categoría inicial sin inventar cambios históricos. */
  public void registrarCategoriaInicial() {
    if (categoria != null && historialCategorias.isEmpty()) {
      historialCategorias.add(categoria);
    }
  }

  /** Cambia la categoría conservando la secuencia de categorías efectivamente asignadas. */
  public void cambiarCategoria(String nuevaCategoria) {
    if (nuevaCategoria == null || nuevaCategoria.isBlank()) {
      throw new IllegalArgumentException("La categoría es obligatoria");
    }
    registrarCategoriaInicial();
    if (!Objects.equals(categoria, nuevaCategoria)) {
      categoria = nuevaCategoria;
      historialCategorias.add(nuevaCategoria);
    }
  }

}